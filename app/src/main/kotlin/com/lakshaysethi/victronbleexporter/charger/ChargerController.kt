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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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

    /** A set-mode operation only succeeded when the readback matches the requested state. */
    private fun ChargerOpResult.verifyWriteSucceeded(on: Boolean): ChargerOpResult {
        if (!success || ChargerProtocol.modeMatchesRequest(mode, on)) return this
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
        val session = Session(mac)
        try {
            if (!session.start()) {
                return session.fail("could not start BLE session ($opName)")
            }
            if (!session.awaitConnected()) {
                return session.fail("connect timed out ($opName)")
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

    /** One connect/operate/disconnect session. */
    private inner class Session(private val mac: String) {

        private val connectedLatch = CountDownLatch(1)
        private val servicesLatch = CountDownLatch(1)

        private val gattRef = AtomicReference<BluetoothGatt?>()
        private val connected = AtomicInteger(0) // 0 unknown, 1 ok, 2 failed
        private val modeMonitor = Object()
        private var modeValue = -1
        private var readbackClosed = false

        private val registers = LinkedHashMap<Int, ByteArray>()
        private val registersLock = Any()

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
            val ok = mode != null
            val text = ChargerProtocol.chargerModeText(mode)
            val message = if (ok) "Charger state: $text (mode=$mode)" else "No state readback received"
            log(message)
            return ChargerOpResult(success = ok, mode = mode, message = message)
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
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                log("Services discovered (status=$status)")
                servicesLatch.countDown()
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onCharacteristicChanged(gatt, characteristic, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleNotification(value)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                log("Write callback (char=${shortUuid(characteristic.uuid.toString())}, status=$status — 0 = OK, 133 = write-without-response quirk)")
            }
        }

        private fun handleNotification(value: ByteArray) {
            val parsed = ChargerProtocol.parseRegisterValues(value)
            if (parsed.isEmpty()) {
                log("Notify: ${value.toHex()}")
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
                ChargerProtocol.CONTROL_UUID,
                ChargerProtocol.SINGLE_UUID,
                ChargerProtocol.BULK_UUID,
            )
            for (uuid in uuids) {
                val char = try {
                    service.getCharacteristic(java.util.UUID.fromString(uuid))
                } catch (e: Exception) {
                    null
                } ?: continue
                if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) continue
                try {
                    gatt.setCharacteristicNotification(char, true)
                    val cccd = char.getDescriptor(CCCD_UUID) ?: continue
                    writeDescriptor(gatt, cccd, byteArrayOf(0x01, 0x00))
                    log("Notifications enabled on $uuid")
                } catch (e: SecurityException) {
                    error("cannot enable notifications: ${e.message}")
                }
            }
            sleepQuietly(200)
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

        fun rearmModeReadback() {
            synchronized(modeMonitor) { modeValue = -1 }
        }

        fun requestModeReadback() {
            log("Requesting device-mode readback")
            writeFrames(
                listOf(
                    ChargerProtocol.SINGLE_UUID to ChargerProtocol.makeReadFrame(ChargerProtocol.REG_DEVICE_MODE),
                    ChargerProtocol.CONTROL_UUID to ChargerProtocol.pollFrame(),
                ),
            )
        }

        fun awaitModeReadback(): Int? {
            val deadline = System.currentTimeMillis() + READBACK_TIMEOUT_MS
            val mode: Int
            synchronized(modeMonitor) {
                while (modeValue < 0 && !readbackClosed) {
                    val remaining = deadline - System.currentTimeMillis()
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
            if (mode < 0) {
                error("no device-mode readback within ${READBACK_TIMEOUT_MS}ms (device may be out of range or busy in VictronConnect)")
                return null
            }
            return mode
        }

        private fun writeFrames(frames: List<Pair<String, ByteArray>>) {
            val gatt = gattRef.get() ?: return
            for ((uuid, payload) in frames) {
                val char = try {
                    gatt.getService(java.util.UUID.fromString(ChargerProtocol.SERVICE_UUID))
                        ?.getCharacteristic(java.util.UUID.fromString(uuid))
                } catch (e: Exception) {
                    null
                } ?: continue
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }
                    log("Write ${shortUuid(uuid)}: ${payload.toHex()}")
                } catch (e: SecurityException) {
                    error("write failed (permission): ${e.message}")
                    return
                }
                sleepQuietly(WRITE_GAP_MS)
            }
            sleepQuietly(150)
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
        const val READBACK_TIMEOUT_MS = 6_000L
        const val WRITE_GAP_MS = 60L

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
