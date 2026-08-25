package com.lakshaysethi.victronbleexporter.charger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ChargerController"

/**
 * Bluetooth GATT client for the Victron SmartSolar charger-control service
 * ([ChargerProtocol.SERVICE_UUID]).
 *
 * One connection is opened per operation (read state / set mode), which keeps
 * the logic simple and robust: connect, run the session handshake, do the
 * register read/write, read back the device-mode register, disconnect.
 *
 * Threading: every operation runs on the caller's background coroutine thread
 * (never the main thread) and blocks on latches that the BLE callbacks release
 * on a dedicated [HandlerThread] — so there is no callback/waiter deadlock.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked in start() and every BLE call is wrapped against SecurityException
class ChargerController(private val context: Context) {

    private val bleThread: HandlerThread = HandlerThread("charger-ble").apply { start() }
    private val bleHandler: Handler = Handler(bleThread.looper)

    /** Serializes BLE sessions so a manual tap and the schedule never collide on one GATT connection. */
    private val opMutex = Mutex()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter
        } catch (e: Exception) {
            Log.e(TAG, "No BluetoothManager", e)
            null
        }
    }

    /** Read the charger mode (register 0x0200) from the device. */
    suspend fun readMode(mac: String): ChargerOpResult = opMutex.withLock {
        withContext(Dispatchers.IO) {
            runSession(mac, "read") { session ->
                session.requestModeReadback()
            }
        }
    }

    /** Enable (on = true) or disable the charger on the device. */
    suspend fun setMode(mac: String, on: Boolean): ChargerOpResult = opMutex.withLock {
        withContext(Dispatchers.IO) {
            val result = runSession(mac, if (on) "enable" else "disable") { session ->
                session.writeMode(on)
                session.rearmModeReadback()
                session.requestModeReadback()
            }
            result.verifyWriteSucceeded(on)
        }
    }

    /** Read battery / voltage settings from the device (same GATT service, writable regs). */
    suspend fun readVoltageSettings(mac: String): VoltageSettingsResult = opMutex.withLock {
        withContext(Dispatchers.IO) { doReadVoltageSettings(mac) }
    }

    /** Background poll: skip entirely if a user tap or schedule already owns the GATT session. */
    suspend fun tryReadVoltageSettings(mac: String): VoltageSettingsResult? {
        if (!opMutex.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) { doReadVoltageSettings(mac) }
        } finally {
            opMutex.unlock()
        }
    }

    private fun doReadVoltageSettings(mac: String): VoltageSettingsResult =
        runVoltageSession(mac, "readVoltageSettings") { session ->
            session.requestVoltageReadback()
        }

    /** Write battery system-voltage setting (register 0xEDEF, e.g. 12/24/48 V) with readback. */
    suspend fun setBatteryVoltageSetting(mac: String, volts: Int): VoltageSettingsResult = opMutex.withLock {
        withContext(Dispatchers.IO) {
            runVoltageSession(mac, "setBatteryVoltageSetting=$volts") { session ->
                session.writeBatteryVoltageSetting(volts)
                session.rearmVoltageReadback()
                session.requestVoltageReadback()
            }
        }
    }

    /** Write absorption / float voltages (0xEDF7 / 0xEDF6) with readback. Null = leave unchanged. */
    suspend fun setChargingVoltages(
        mac: String,
        absorptionVolts: Double?,
        floatVolts: Double?,
    ): VoltageSettingsResult = opMutex.withLock {
        withContext(Dispatchers.IO) {
            runVoltageSession(mac, "setChargingVoltages abs=$absorptionVolts float=$floatVolts") { session ->
                if (absorptionVolts != null) session.writeChargingVoltage(ChargerProtocol.REG_ABSORPTION_VOLTAGE, absorptionVolts)
                if (floatVolts != null) session.writeChargingVoltage(ChargerProtocol.REG_FLOAT_VOLTAGE, floatVolts)
                session.rearmVoltageReadback()
                session.requestVoltageReadback()
            }
        }
    }

    /** A set-mode operation only succeeded when the readback matches the requested state. */
    private fun ChargerOpResult.verifyWriteSucceeded(on: Boolean): ChargerOpResult {
        if (!success) return this
        if (mode == null) return this // GATT write landed; this firmware often skips the 0x0200 echo
        if (ChargerProtocol.modeMatchesRequest(mode, on)) return this
        val readback = mode?.let { "${ChargerProtocol.chargerModeText(it)} (mode=$it)" } ?: "none"
        val message = "Readback ($readback) does not match requested ${if (on) "ON" else "OFF"} — write may not have taken effect"
        ChargerDebugLog.append("ERROR: $message")
        return ChargerOpResult(success = false, mode = mode, message = message)
    }

    /** Release the BLE handler thread (call from the service's onDestroy). */
    fun shutdown() {
        try {
            bleHandler.removeCallbacksAndMessages(null)
            bleThread.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown failed", e)
        }
    }

    private fun runSession(mac: String, opName: String, op: (Session) -> Unit): ChargerOpResult {
        var lastFail: ChargerOpResult? = null
        repeat(3) { attempt ->
            if (attempt > 0) sleepQuietly(2_500)
            val session = Session(mac)
            try {
                if (!session.start()) {
                    lastFail = session.fail("could not start BLE session ($opName)")
                    return@repeat
                }
                if (!session.awaitConnected()) {
                    lastFail = session.fail("connect failed ($opName) attempt ${attempt + 1}/3")
                    return@repeat
                }
                if (!session.awaitServices()) {
                    return session.fail("no services discovered — is the MPPT in range and not bonded to another phone? (pairing PIN is usually 000000)")
                }
                if (!session.hasChargerService()) {
                    return session.fail("device does not expose the Victron charger service ${ChargerProtocol.SERVICE_UUID}")
                }
                session.enableNotifications()
                session.runInitSequence()
                op(session)
                val mode = session.awaitModeReadback()
                if (opName == "read" && mode == null) {
                    return session.fail("No state readback received")
                }
                return session.finish(mode)
            } catch (e: SecurityException) {
                return session.fail("BLE permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Session failed", e)
                return session.fail("unexpected error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                session.close()
            }
        }
        return lastFail ?: ChargerOpResult(false, null, "connect failed ($opName)")
    }

    // ---- voltage settings (same one-session-per-op pattern, different registers) ----

    private fun runVoltageSession(mac: String, opName: String, op: (Session) -> Unit): VoltageSettingsResult {
        val session = Session(mac)
        try {
            if (!session.start()) return session.failVoltage("could not start BLE session ($opName)")
            if (!session.awaitConnected()) return session.failVoltage("connect timed out ($opName)")
            if (!session.awaitServices()) return session.failVoltage("no services discovered — is the MPPT in range and not bonded to another phone? (pairing PIN is usually 000000)")
            if (!session.hasChargerService()) return session.failVoltage("device does not expose the Victron charger service ${ChargerProtocol.SERVICE_UUID}")
            session.enableNotifications()
            session.runInitSequence()
            op(session)
            val settings = session.awaitVoltageReadback()
            return session.finishVoltage(settings)
        } catch (e: SecurityException) {
            return session.failVoltage("BLE permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Voltage session failed", e)
            return session.failVoltage("unexpected error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            session.close()
        }
    }

    /** One connect/operate/disconnect session. */
    private inner class Session(private val mac: String) {

        private val connectedLatch = CountDownLatch(1)
        private val servicesLatch = CountDownLatch(1)

        private val gattRef = AtomicReference<BluetoothGatt?>()
        private val connected = AtomicInteger(0) // 0 unknown, 1 ok, 2 failed
        private val modeMonitor = Object()
        private var modeValue = -1
        private var readbackClosed = false

        // Voltage settings use the same notification stream but a separate monitor so reads
        // do not steal the mode latch and vice versa.
        private val voltageMonitor = Object()
        private var voltageReadbackClosed = false

        private val registers = LinkedHashMap<Int, ByteArray>()
        private val registersLock = Any()

        /** Set when a characteristic write was not accepted; waiters fail fast instead of sitting out the timeout. */
        private var writeFailed = false
        @Volatile private var descLatch = CountDownLatch(1)
        @Volatile private var mtuLatch = CountDownLatch(1)
        private var notifyBuf = ByteArray(0)

        /** One in-flight write at a time. An unacknowledged no-response write wedges the connection and every later write is refused. */
        @Volatile private var writeLatch = CountDownLatch(1)
        @Volatile private var lastWriteStatus: Int = BluetoothGatt.GATT_SUCCESS

        private val steps = mutableListOf<String>()
        private var firstError: String? = null

        private fun log(line: String) {
            steps.add(line)
            Log.i(TAG, line)
            ChargerDebugLog.append(line)
        }

        private fun error(line: String) {
            if (firstError == null) firstError = line
            log("ERROR: $line")
        }

        fun fail(message: String): ChargerOpResult {
            error(message)
            return ChargerOpResult(success = false, mode = null, message = message)
        }

        fun finish(mode: Int?): ChargerOpResult {
            if (writeFailed) {
                return ChargerOpResult(success = false, mode = mode, message = firstError ?: "write failed")
            }
            val text = ChargerProtocol.chargerModeText(mode)
            val message = if (mode != null) {
                "Charger state: $text (mode=$mode)"
            } else {
                "GATT writes accepted; no 0x0200 echo (confirm from Instant Readout)"
            }
            log(message)
            return ChargerOpResult(success = true, mode = mode, message = message)
        }

        private fun adapter(): BluetoothAdapter? = bluetoothAdapter

        /** Connect + start discovery. Returns false on immediate failure. */
        fun start(): Boolean {
            val adapter = adapter()
            if (adapter == null) {
                error("Bluetooth is not available on this device")
                return false
            }
            if (!adapter.isEnabled) {
                error("Bluetooth is disabled")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                error("BLUETOOTH_CONNECT permission missing")
                return false
            }
            val device = try {
                adapter.getRemoteDevice(mac)
            } catch (e: IllegalArgumentException) {
                error("Invalid MAC address: $mac")
                return false
            }
            log("Connecting to $mac (${device.name ?: "?"})")
            val gatt = try {
                device.connectGatt(
                    context.applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    bleHandler,
                )
            } catch (e: Exception) {
                error("connectGatt failed: ${e.message}")
                return false
            }
            gattRef.set(gatt)
            return true
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("Connected (status=$status) — discovering services")
                        connected.set(1)
                        connectedLatch.countDown()
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log("Disconnected (status=$status)")
                        connected.compareAndSet(0, 2)
                        connectedLatch.countDown()
                        servicesLatch.countDown()
                        synchronized(modeMonitor) {
                            readbackClosed = true
                            modeMonitor.notifyAll()
                        }
                        synchronized(voltageMonitor) {
                            voltageReadbackClosed = true
                            voltageMonitor.notifyAll()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                log("Services discovered (status=$status)")
                servicesLatch.countDown()
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val v = characteristic.value ?: return
                if (v.isNotEmpty()) handleNotification(v)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (value.isNotEmpty()) handleNotification(value)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                log("Write callback (char=${shortUuid(characteristic.uuid.toString())}, status=$status)")
                lastWriteStatus = status
                writeLatch.countDown()
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                log("Descriptor write status=$status")
                descLatch.countDown()
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                log("MTU $mtu status=$status")
                mtuLatch.countDown()
            }
        }

        private fun handleNotification(value: ByteArray) {
            notifyBuf = notifyBuf + value
            if (notifyBuf.size > 512) notifyBuf = notifyBuf.copyOfRange(notifyBuf.size - 256, notifyBuf.size)
            val (parsed, leftover) = ChargerProtocol.parseRegisterStream(notifyBuf)
            notifyBuf = leftover
            if (parsed.isEmpty()) {
                if (value.isNotEmpty()) log("Notify: ${value.toHex()}")
                return
            }
            for ((registerId, raw) in parsed) {
                synchronized(registersLock) { registers[registerId] = raw }
                log("Notify reg 0x${registerId.toString(16).padStart(4, '0')} = ${raw.toHex()}")
            }
            ChargerProtocol.chargerModeOf(parsed)?.let { mode ->
                synchronized(modeMonitor) {
                    modeValue = mode
                    modeMonitor.notifyAll()
                }
                log("Device mode readback: ${ChargerProtocol.chargerModeText(mode)} (mode=$mode)")
            }
            // Any voltage register arrival completes a voltage readback wait (even partial sets
            // are surfaced — the caller decides what was written vs what came back).
            val hasVoltage = parsed.keys.any {
                it == ChargerProtocol.REG_BATTERY_VOLTAGE_SETTING ||
                    it == ChargerProtocol.REG_ABSORPTION_VOLTAGE ||
                    it == ChargerProtocol.REG_FLOAT_VOLTAGE ||
                    it == ChargerProtocol.REG_EQUALISATION_VOLTAGE ||
                    it == ChargerProtocol.REG_CHARGER_VOLTAGE ||
                    it == ChargerProtocol.REG_PANEL_VOLTAGE
            }
            if (hasVoltage) {
                synchronized(voltageMonitor) { voltageMonitor.notifyAll() }
            }
        }

        fun awaitConnected(): Boolean {
            val ok = await(connectedLatch, CONNECT_TIMEOUT_MS)
            if (!ok) error("connect timeout after ${CONNECT_TIMEOUT_MS}ms")
            return ok && connected.get() == 1
        }

        fun awaitServices(): Boolean = await(servicesLatch, SERVICES_TIMEOUT_MS)

        fun hasChargerService(): Boolean {
            val gatt = gattRef.get() ?: return false
            return try {
                gatt.services.any { it.uuid.toString().lowercase() == ChargerProtocol.SERVICE_UUID }
            } catch (e: Exception) {
                false
            }
        }

        fun enableNotifications() {
            val gatt = gattRef.get() ?: return
            val service = try {
                gatt.getService(java.util.UUID.fromString(ChargerProtocol.SERVICE_UUID))
            } catch (e: Exception) {
                null
            } ?: return
            val uuids = listOf(
                ChargerProtocol.SINGLE_UUID,
                ChargerProtocol.BULK_UUID,
            )
            for (uuid in uuids) {
                val char = try {
                    service.getCharacteristic(java.util.UUID.fromString(uuid))
                } catch (e: Exception) {
                    null
                } ?: continue
                val props = char.properties
                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (!canNotify) {
                    log("Skip notify: ${shortUuid(uuid)} has no NOTIFY/INDICATE (props=$props)")
                    continue
                }
                try {
                    gatt.setCharacteristicNotification(char, true)
                    val cccd = char.getDescriptor(CCCD_UUID)
                    if (cccd == null) {
                        log("No CCCD for ${shortUuid(uuid)} — notifications may not arrive")
                        continue
                    }
                    descLatch = CountDownLatch(1)
                    writeDescriptor(gatt, cccd, byteArrayOf(0x01, 0x00))
                    if (!await(descLatch, 1_500L)) {
                        log("CCCD write not confirmed for ${shortUuid(uuid)} — continuing")
                    }
                    log("Notifications enabled on $uuid")
                } catch (e: SecurityException) {
                    error("cannot enable notifications: ${e.message}")
                }
            }
            // Do not requestMtu: this SmartSolar answers status=4 and then disconnects (19).
            sleepQuietly(250)
        }

        fun runInitSequence() {
            log("Sending session handshake (${ChargerProtocol.INIT_SEQUENCE.size} frames)")
            writeFrames(ChargerProtocol.INIT_SEQUENCE)
        }

        fun writeMode(on: Boolean) {
            val frame = ChargerProtocol.makeChargerModeWriteFrame(on)
            log("Writing charger ${if (on) "ON" else "OFF"} -> ${frame.toHex()}")
            writeFrames(listOf(ChargerProtocol.SINGLE_UUID to frame))
        }

        fun writeBatteryVoltageSetting(volts: Int) {
            val frame = ChargerProtocol.makeBatteryVoltageSettingWriteFrame(volts)
            log("Writing battery voltage setting ${volts}V -> ${frame.toHex()}")
            writeFrames(listOf(ChargerProtocol.SINGLE_UUID to frame))
        }

        fun writeChargingVoltage(registerId: Int, voltageVolts: Double) {
            val frame = ChargerProtocol.makeVoltageWriteFrame(registerId, voltageVolts)
            log("Writing reg 0x${registerId.toString(16)} ${voltageVolts}V -> ${frame.toHex()}")
            writeFrames(listOf(ChargerProtocol.SINGLE_UUID to frame))
        }

        fun rearmModeReadback() {
            synchronized(modeMonitor) { modeValue = -1 }
        }

        fun rearmVoltageReadback() {
            synchronized(voltageMonitor) { voltageReadbackClosed = false }
            // Clear latched voltage regs so the await does not return stale data from the handshake.
            synchronized(registersLock) {
                registers.remove(ChargerProtocol.REG_BATTERY_VOLTAGE_SETTING)
                registers.remove(ChargerProtocol.REG_ABSORPTION_VOLTAGE)
                registers.remove(ChargerProtocol.REG_FLOAT_VOLTAGE)
                registers.remove(ChargerProtocol.REG_EQUALISATION_VOLTAGE)
                registers.remove(ChargerProtocol.REG_CHARGER_VOLTAGE)
                registers.remove(ChargerProtocol.REG_PANEL_VOLTAGE)
            }
        }

        fun requestModeReadback() {
            log("Requesting device-mode readback")
            writeFrames(
                listOf(
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_DEVICE_MODE),
                ),
            )
        }

        fun requestVoltageReadback() {
            log("Requesting voltage-settings readback")
            writeFrames(
                listOf(
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_BATTERY_VOLTAGE_SETTING),
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_ABSORPTION_VOLTAGE),
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_FLOAT_VOLTAGE),
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_EQUALISATION_VOLTAGE),
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_CHARGER_VOLTAGE),
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_PANEL_VOLTAGE),
                    ChargerProtocol.CONTROL_UUID to ChargerProtocol.pollFrame(),
                ),
            )
        }

        fun awaitVoltageReadback(): VoltageSettings {
            val deadline = SystemClock.elapsedRealtime() + READBACK_TIMEOUT_MS
            synchronized(voltageMonitor) {
                while (!voltageReadbackClosed && !writeFailed) {
                    val haveAny = synchronized(registersLock) {
                        registers.keys.any {
                            it == ChargerProtocol.REG_BATTERY_VOLTAGE_SETTING ||
                                it == ChargerProtocol.REG_ABSORPTION_VOLTAGE ||
                                it == ChargerProtocol.REG_FLOAT_VOLTAGE
                        }
                    }
                    if (haveAny) break
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    try {
                        voltageMonitor.wait(minOf(remaining, 500L))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            // Panel voltage is requested last; give its notify a beat after the first settings frame.
            if (synchronized(registersLock) { !registers.containsKey(ChargerProtocol.REG_PANEL_VOLTAGE) }) {
                sleepQuietly(400)
            }
            return snapshotVoltageSettings()
        }

        private fun snapshotVoltageSettings(): VoltageSettings = synchronized(registersLock) {
            val copy = LinkedHashMap(registers)
            VoltageSettings(
                batteryVoltageSetting = copy[ChargerProtocol.REG_BATTERY_VOLTAGE_SETTING]?.let { ChargerProtocol.decodeBatteryVoltageSetting(it) },
                absorptionVolts = copy[ChargerProtocol.REG_ABSORPTION_VOLTAGE]?.let { ChargerProtocol.decodeVoltage(it) },
                floatVolts = copy[ChargerProtocol.REG_FLOAT_VOLTAGE]?.let { ChargerProtocol.decodeVoltage(it) },
                equalisationVolts = copy[ChargerProtocol.REG_EQUALISATION_VOLTAGE]?.let { ChargerProtocol.decodeVoltage(it) },
                chargerVolts = copy[ChargerProtocol.REG_CHARGER_VOLTAGE]?.let { ChargerProtocol.decodeVoltage(it) },
                panelVolts = ChargerProtocol.panelVoltageOf(copy[ChargerProtocol.REG_PANEL_VOLTAGE]),
            )
        }

        fun failVoltage(message: String): VoltageSettingsResult {
            error(message)
            return VoltageSettingsResult(success = false, settings = snapshotVoltageSettings(), message = message)
        }

        fun finishVoltage(settings: VoltageSettings): VoltageSettingsResult {
            val any = settings.batteryVoltageSetting != null || settings.absorptionVolts != null || settings.floatVolts != null
            val message = if (any) "Voltage settings: $settings" else "No voltage settings readback received"
            log(message)
            return VoltageSettingsResult(success = any, settings = settings, message = message)
        }

        fun awaitModeReadback(): Int? {
            val deadline = SystemClock.elapsedRealtime() + READBACK_TIMEOUT_MS
            var mode: Int
            synchronized(modeMonitor) {
                while (modeValue < 0 && !readbackClosed && !writeFailed) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    try {
                        modeMonitor.wait(remaining)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                mode = modeValue
            }
            if (mode < 0 && !writeFailed) {
                // One extra poll — the MPPT often emits 0x0200 only after a second f941.
                log("No mode yet — extra read (0x82)")
                writeFrames(
                    listOf(
                        ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame82(ChargerProtocol.REG_DEVICE_MODE),
                    ),
                )
                synchronized(modeMonitor) {
                    val extra = READBACK_TIMEOUT_MS / 2
                    val until = SystemClock.elapsedRealtime() + extra
                    while (modeValue < 0 && !readbackClosed && !writeFailed && SystemClock.elapsedRealtime() < until) {
                        try {
                            modeMonitor.wait(300L)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    mode = modeValue
                }
            }
            if (mode < 0) {
                error("no device-mode readback within ${READBACK_TIMEOUT_MS}ms (device may be out of range or busy in VictronConnect)")
                return null
            }
            return mode
        }

        private fun writeFrames(frames: List<Pair<String, ByteArray>>) {
            if (writeFailed) return
            val gatt = gattRef.get() ?: return
            for ((uuid, payload) in frames) {
                val char = try {
                    gatt.getService(java.util.UUID.fromString(ChargerProtocol.SERVICE_UUID))
                        ?.getCharacteristic(java.util.UUID.fromString(uuid))
                } catch (e: Exception) {
                    null
                } ?: continue
                try {
                    val writeType = ChargerProtocol.writeTypeForProperties(char.properties)
                    writeLatch = CountDownLatch(1)
                    lastWriteStatus = BluetoothGatt.GATT_SUCCESS
                    var queued = queueWrite(gatt, char, payload, writeType)
                    if (!queued) {
                        sleepQuietly(120)
                        queued = queueWrite(gatt, char, payload, writeType)
                    }
                    if (!queued) {
                        error("write not accepted by the stack (${shortUuid(uuid)}, props=${char.properties}, writeType=$writeType)")
                        writeFailed = true
                        return
                    }
                    log("Write ${shortUuid(uuid)} type=$writeType props=${char.properties}: ${payload.toHex()}")
                    // Android still delivers onCharacteristicWrite for WRITE_NO_RESPONSE once the
                    // local queue accepts it. Starting the next frame before that callback races
                    // the one-in-flight GATT write queue and the MPPT drops register replies.
                    if (!await(writeLatch, WRITE_CALLBACK_TIMEOUT_MS)) {
                        error("device did not acknowledge write (${shortUuid(uuid)}) within ${WRITE_CALLBACK_TIMEOUT_MS}ms")
                        writeFailed = true
                        return
                    }
                    if (!ChargerProtocol.gattWriteAccepted(true, lastWriteStatus)) {
                        error("device rejected write (${shortUuid(uuid)}): GATT status $lastWriteStatus")
                        writeFailed = true
                        return
                    }
                } catch (e: SecurityException) {
                    error("write failed (permission): ${e.message}")
                    writeFailed = true
                    return
                }
                sleepQuietly(WRITE_GAP_MS)
            }
            sleepQuietly(150)
        }

        private fun queueWrite(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            payload: ByteArray,
            writeType: Int,
        ): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                char.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }

        private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } catch (e: SecurityException) {
                error("descriptor write failed: ${e.message}")
            }
        }

        fun close() {
            val gatt = gattRef.getAndSet(null)
            if (gatt != null) {
                try {
                    gatt.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "disconnect failed", e)
                }
                try {
                    gatt.close()
                } catch (e: Exception) {
                    Log.w(TAG, "close failed", e)
                }
            }
            log("Session closed")
        }
    }

    private fun await(latch: CountDownLatch, timeoutMs: Long): Boolean =
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun shortUuid(uuid: String): String = uuid.substring(0, 8)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val SERVICES_TIMEOUT_MS = 12_000L
        const val READBACK_TIMEOUT_MS = 8_000L
        const val WRITE_GAP_MS = 80L
        const val WRITE_CALLBACK_TIMEOUT_MS = 4_000L

        val CCCD_UUID: java.util.UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** Outcome of a charger control operation. */
data class ChargerOpResult(
    val success: Boolean,
    val mode: Int?,
    val message: String,
) {
    val modeText: String get() = ChargerProtocol.chargerModeText(mode)
}

/** Live voltage settings snapshot (read + write-with-readback share the same shape). */
data class VoltageSettings(
    val batteryVoltageSetting: Int? = null, // 0xEDEF, V as integer (12/24/48 …)
    val absorptionVolts: Double? = null, // 0xEDF7, V
    val floatVolts: Double? = null, // 0xEDF6, V
    val equalisationVolts: Double? = null, // 0xEDF4, V
    val chargerVolts: Double? = null, // 0xEDD5, live read-only, V
    val panelVolts: Double? = null, // 0xEDBB, PV input; null = not reported or night-time NA
)

data class VoltageSettingsResult(
    val success: Boolean,
    val settings: VoltageSettings,
    val message: String,
)
