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
import com.lakshaysethi.victronbleexporter.R
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
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}