package com.lakshaysethi.victronbleexporter.service

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

    // Device encryption keys: MAC -> key (hex)
    private val deviceKeys = mutableMapOf<String, String>()

    // For demo, allow adding keys from UI (we'll expose a simple way)
    fun addDeviceKey(mac: String, key: String) {
        deviceKeys[mac.uppercase()] = key.trim()
        Log.i(TAG, "Key added for $mac")
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        createNotificationChannel()

        cloudflaredManager = CloudflaredManager(this)
        prometheusExporter = PrometheusExporter(9100)
        prometheusExporter.startServer()

        startForegroundServiceNotification("Starting...")

        // Start BLE scan
        startBleScan()

        // Start cloudflared if we have a token (from SharedPrefs or passed in)
        // For now, we support runtime start via intent extras or UI
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
                        cloudflaredManager.startQuickTunnel(9100) { status ->
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
                val device = result.device
                val scanRecord = result.scanRecord ?: return
                val mfgData = scanRecord.getManufacturerSpecificData(0x02E1) ?: return

                val mac = device.address.uppercase()
                val rssi = result.rssi

                val key = deviceKeys[mac]
                val parsed = VictronParser.parseAdvertisement(mac, mfgData, rssi, key)

                if (parsed != null) {
                    MetricsStore.update(parsed)
                    Log.d(TAG, "Parsed ${parsed.mac} : ${parsed.data}")
                } else {
                    // Still record that we saw a Victron device (even without key)
                    Log.d(TAG, "Saw Victron adv from $mac (no key or parse fail)")
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
        val intent = Intent(this, Class.forName("com.lakshaysethi.victronbleexporter.MainActivity"))
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder, use proper icon later
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        stopBleScan()
        prometheusExporter.stopServer()
        cloudflaredManager.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}