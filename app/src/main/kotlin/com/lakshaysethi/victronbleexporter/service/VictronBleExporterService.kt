package com.lakshaysethi.victronbleexporter.service

import android.os.PowerManager

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.R
import com.lakshaysethi.victronbleexporter.charger.ChargerController
import com.lakshaysethi.victronbleexporter.charger.ChargerDebugLog
import com.lakshaysethi.victronbleexporter.charger.ChargerSchedule
import com.lakshaysethi.victronbleexporter.data.ChargerScheduleStore
import com.lakshaysethi.victronbleexporter.data.DeviceRepository
import com.lakshaysethi.victronbleexporter.exporter.DiscoveredDevicesStore
import com.lakshaysethi.victronbleexporter.exporter.MetricsStore
import com.lakshaysethi.victronbleexporter.exporter.PrometheusExporter
import com.lakshaysethi.victronbleexporter.parser.VictronParser
import com.lakshaysethi.victronbleexporter.tunnel.CloudflaredManager
import kotlinx.coroutines.*
import java.util.*

private const val TAG = "VictronBleExporterService"
private const val NOTIFICATION_ID = 1337
private const val CHANNEL_ID = "victron_exporter_channel"

class VictronBleExporterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private lateinit var prometheusExporter: PrometheusExporter
    private lateinit var cloudflaredManager: CloudflaredManager
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var deviceRepository: DeviceRepository

    // Charger control (BLE write of register 0x0200 device mode) + daily schedule.
    private val chargerController: ChargerController by lazy { ChargerController(this) }
    private val chargerScheduleStore: ChargerScheduleStore by lazy { ChargerScheduleStore(this) }
    private var lastScheduledMode: Boolean? = null
    private var scheduleRetryAt = 0L

    // Device encryption keys: MAC -> key (hex) - in-memory cache, persisted via DeviceRepository
    private val deviceKeys = mutableMapOf<String, String>()

    fun addDeviceKey(mac: String, key: String) {
        val normalizedMac = DeviceRepository.normalizeMacInput(mac)
        val cleanKey = DeviceRepository.normalizeKeyInput(key)
        if (!DeviceRepository.isValidKey(cleanKey)) {
            Log.w(TAG, "Invalid key format for $mac: length ${cleanKey.length}")
            // Still allow? For UX, reject if clearly invalid
            if (cleanKey.length != 32) return
        }
        deviceKeys[normalizedMac] = cleanKey
        try {
            deviceRepository.saveDevice(normalizedMac, cleanKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist key for $normalizedMac", e)
        }
        DiscoveredDevicesStore.markHasKey(normalizedMac, true)
        Log.i(TAG, "Key added & persisted for $normalizedMac")
    }

    private fun loadPersistedKeys() {
        try {
            if (!::deviceRepository.isInitialized) {
                deviceRepository = DeviceRepository(this)
            }
            val all = deviceRepository.getAllDevices()
            deviceKeys.clear()
            deviceKeys.putAll(all.mapKeys { it.key.uppercase() })
            Log.i(TAG, "Loaded ${deviceKeys.size} persisted keys: ${deviceKeys.keys}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted keys", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }

        try {
            deviceRepository = DeviceRepository(this)
            loadPersistedKeys()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init DeviceRepository", e)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VictronBleExporter::WakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakeLock", e)
        }

        try {
            cloudflaredManager = CloudflaredManager(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init CloudflaredManager", e)
            cloudflaredManager = CloudflaredManager(this) // still init, but log
        }

        try {
            prometheusExporter = PrometheusExporter(5338)
            prometheusExporter.startServer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start exporter", e)
        }

        try {
            startForegroundServiceNotification("Starting...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            // Fallback without foreground type
            try {
                val notification = buildNotification("Starting...")
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback foreground failed", e2)
            }
        }

        // Start BLE scan - may fail if BT off or permission missing, handled inside
        try {
            startBleScan()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan in onCreate", e)
        }

        startChargerScheduleLoop()
    }

    // ---- Charger control (enable/disable over BLE + daily schedule) ----

    private fun startChargerScheduleLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    enforceChargerSchedule()
                } catch (e: Exception) {
                    Log.w(TAG, "Charger schedule tick failed", e)
                }
                delay(30_000)
            }
        }
    }

    /**
     * Enforces the configured enable/disable window while the service runs.
     * Manual overrides pause the schedule until the next window boundary.
     */
    private suspend fun enforceChargerSchedule() {
        val settings = chargerScheduleStore.load()
        if (settings.chargerMac.isBlank()) return
        val now = System.currentTimeMillis()
        if (settings.manualOverrideUntil in 1..now) {
            chargerScheduleStore.clearOverride()
            AppState.chargerOverrideUntil = 0L
            ChargerDebugLog.append("Manual override ended — schedule resumes")
        }
        if (!settings.scheduleEnabled) return
        if (chargerScheduleStore.manualOverrideUntil > now) return // manual override active
        if (now < scheduleRetryAt) return

        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val desiredOn = ChargerSchedule.scheduledOn(minutes, settings.enableMinutes, settings.disableMinutes)
        if (desiredOn == lastScheduledMode) return

        ChargerDebugLog.append(
            "Schedule tick: window=${ChargerSchedule.formatMinutes(settings.enableMinutes)}-${ChargerSchedule.formatMinutes(settings.disableMinutes)}" +
                " -> charger ${if (desiredOn) "ON" else "OFF"}"
        )
        val result = chargerController.setMode(settings.chargerMac, desiredOn)
        if (result.success) {
            lastScheduledMode = desiredOn
            AppState.chargerMode = result.mode
            AppState.chargerMac = settings.chargerMac
            AppState.chargerStateUpdatedAt = System.currentTimeMillis()
            AppState.chargerLastAction = "Schedule: charger ${if (desiredOn) "ENABLED" else "DISABLED"} (${result.modeText})"
            AppState.chargerLastError = null
        } else {
            AppState.chargerLastAction = "Schedule apply failed: ${result.message}"
            AppState.chargerLastError = result.message
            scheduleRetryAt = now + 10 * 60_000L
        }
    }

    private suspend fun performChargerSet(mac: String, enable: Boolean) {
        val store = chargerScheduleStore
        store.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = if (enable) "Enabling charger…" else "Disabling charger…"
        AppState.chargerLastError = null
        ChargerDebugLog.append("Manual ${if (enable) "ENABLE" else "DISABLE"} requested for $mac")
        try {
            val result = chargerController.setMode(mac, enable)
            AppState.chargerMode = result.mode
            AppState.chargerStateUpdatedAt = System.currentTimeMillis()
            if (result.success) {
                // Manual override: pause the schedule until the next window boundary.
                val cal = Calendar.getInstance()
                val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val next = ChargerSchedule.nextTransition(minutes, store.load().enableMinutes, store.load().disableMinutes)
                val until = nextTransitionEpoch(next)
                store.manualOverrideUntil = until
                AppState.chargerOverrideUntil = until
                AppState.chargerLastAction = "Charger ${if (enable) "ENABLED" else "DISABLED"} (${result.modeText})"
                ChargerDebugLog.append("Manual override active until ${ChargerSchedule.formatMinutes(next)}")
            } else {
                AppState.chargerLastAction = "Failed: ${result.message}"
                AppState.chargerLastError = result.message
            }
        } catch (e: Exception) {
            Log.w(TAG, "Charger set failed", e)
            AppState.chargerLastAction = "Failed: ${e.message}"
            AppState.chargerLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
        } finally {
            AppState.chargerBusy = false
        }
    }

    private suspend fun performChargerRead(mac: String) {
        val store = chargerScheduleStore
        store.chargerMac = mac
        AppState.chargerMac = mac
        AppState.chargerBusy = true
        AppState.chargerLastAction = "Reading charger state…"
        AppState.chargerLastError = null
        ChargerDebugLog.append("Manual state read requested for $mac")
        try {
            val result = chargerController.readMode(mac)
            AppState.chargerMode = result.mode
            AppState.chargerStateUpdatedAt = System.currentTimeMillis()
            AppState.chargerLastAction = if (result.success) "Read: ${result.modeText}" else "Read failed: ${result.message}"
            if (!result.success) AppState.chargerLastError = result.message
        } catch (e: Exception) {
            Log.w(TAG, "Charger read failed", e)
            AppState.chargerLastAction = "Read failed: ${e.message}"
            AppState.chargerLastError = e.message
            ChargerDebugLog.append("ERROR: ${e.message}")
        } finally {
            AppState.chargerBusy = false
        }
    }

    private fun saveChargerSchedule(mac: String, enabled: Boolean, enableTime: String, disableTime: String) {
        chargerScheduleStore.save(enabled, enableTime, disableTime, mac)
        AppState.chargerMac = mac
        AppState.chargerOverrideUntil = 0L
        lastScheduledMode = null // force a fresh apply on the next tick
        ChargerDebugLog.append(
            "Schedule saved: $mac ${if (enabled) "enabled" else "disabled"} " +
                "($enableTime → $disableTime). Applies while the app is open."
        )
    }

    private fun nextTransitionEpoch(nextMinutesOfDay: Int): Long {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (nextMinutesOfDay <= nowMinutes) cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, nextMinutesOfDay / 60)
        cal.set(Calendar.MINUTE, nextMinutesOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                "START_TUNNEL" -> {
                    val token = it.getStringExtra("tunnel_token")
                    if (!token.isNullOrBlank()) {
                        cloudflaredManager.startNamedTunnel(token) { status ->
                            updateNotification(status)
                        }
                    } else {
                        // Quick tunnel fallback
                        cloudflaredManager.startQuickTunnel(5338) { status ->
                            updateNotification(status)
                        }
                    }
                }
                "STOP_TUNNEL" -> cloudflaredManager.stop()
                "ADD_KEY" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val key = it.getStringExtra("key") ?: return@let
                    addDeviceKey(mac, key)
                }
                "CHARGER_SET" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val enable = it.getBooleanExtra("enable", true)
                    serviceScope.launch { performChargerSet(mac, enable) }
                }
                "CHARGER_READ" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    serviceScope.launch { performChargerRead(mac) }
                }
                "CHARGER_SCHEDULE_SAVE" -> {
                    val mac = it.getStringExtra("mac") ?: return@let
                    val enabled = it.getBooleanExtra("schedule_enabled", false)
                    val enableTime = it.getStringExtra("enable_time") ?: ChargerSchedule.DEFAULT_ENABLE
                    val disableTime = it.getStringExtra("disable_time") ?: ChargerSchedule.DEFAULT_DISABLE
                    saveChargerSchedule(mac, enabled, enableTime, disableTime)
                }
            }
        }
        updateNotification("Running")
        return START_STICKY
    }

    private fun startBleScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }

        bluetoothLeScanner = adapter.bluetoothLeScanner

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(0x02E1, byteArrayOf(0x10)) // Victron + product adv
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val device = result.device
                    val scanRecord = result.scanRecord ?: return
                    val mfgData = scanRecord.getManufacturerSpecificData(0x02E1) ?: return

                    val mac = device.address.uppercase()
                    val rssi = result.rssi

                    // Extract modelId & recordType without decryption (always visible)
                    val modelId: Int? = if (mfgData.size >= 4) {
                        ((mfgData[3].toInt() and 0xFF) shl 8) or (mfgData[2].toInt() and 0xFF)
                    } else null
                    val recordType: Int? = if (mfgData.size >= 5) mfgData[4].toInt() and 0xFF else null

                    val key = deviceKeys[mac] ?: deviceKeys[mac.uppercase()]
                    val hasKey = !key.isNullOrBlank()

                    val parsed = if (hasKey) {
                        try {
                            VictronParser.parseAdvertisement(mac, mfgData, rssi, key)
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse failed for $mac", e)
                            null
                        }
                    } else null

                    // Always update discovered store for easy UX
                    try {
                        DiscoveredDevicesStore.updateSeen(
                            mac = mac,
                            modelId = modelId,
                            recordType = recordType,
                            rssi = rssi,
                            hasKey = hasKey,
                            parsed = parsed
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update discovered store", e)
                    }

                    if (parsed != null) {
                        MetricsStore.update(parsed)
                        Log.d(TAG, "Parsed ${parsed.mac} : ${parsed.data}")
                    } else {
                        if (!hasKey) {
                            Log.d(TAG, "Saw Victron $mac (model=${modelId?.toString(16)}) without key - showing in discovered list")
                        } else {
                            Log.d(TAG, "Saw Victron adv from $mac (key present but parse fail)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onScanResult exception", e)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed: $errorCode")
            }
        }

        try {
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.i(TAG, "BLE scan started for Victron devices")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission", e)
        }
    }

    private fun stopBleScan() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan", e)
        }
        bluetoothLeScanner = null
        scanCallback = null
    }

    private fun startForegroundServiceNotification(initialText: String) {
        val notification = buildNotification(initialText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        val count = MetricsStore.count()
        val tunnel = cloudflaredManager.tunnelUrl ?: cloudflaredManager.status
        val text = getString(R.string.notification_text, count, tunnel.take(40))
        val notification = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(content: String): Notification {
        val mainIntent = try {
            Intent(this, Class.forName("com.lakshaysethi.victronbleexporter.MainActivity"))
        } catch (e: Exception) {
            Log.w(TAG, "MainActivity class not found via reflection, using package launcher", e)
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        }

        val pendingIntent = try {
            PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create PendingIntent", e)
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        try { stopBleScan() } catch (e: Exception) { Log.w(TAG, "stopBleScan failed", e) }
        try {
            if (::prometheusExporter.isInitialized) prometheusExporter.stopServer()
        } catch (e: Exception) {
            Log.w(TAG, "stop exporter failed", e)
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock release failed", e)
        }
        try {
            if (::cloudflaredManager.isInitialized) cloudflaredManager.stop()
        } catch (e: Exception) {
            Log.w(TAG, "cloudflared stop failed", e)
        }
        try {
            chargerController.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "charger controller shutdown failed", e)
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}