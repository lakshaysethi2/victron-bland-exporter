package com.lakshaysethi.victronbleexporter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lakshaysethi.victronbleexporter.data.DeviceRepository
import com.lakshaysethi.victronbleexporter.diag.AppLog
import com.lakshaysethi.victronbleexporter.diag.Diagnostics
import com.lakshaysethi.victronbleexporter.diag.UpdateChecker
import com.lakshaysethi.victronbleexporter.exporter.DiscoveredDevice
import com.lakshaysethi.victronbleexporter.exporter.DiscoveredDevicesStore
import com.lakshaysethi.victronbleexporter.exporter.MetricsStore
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService
import com.lakshaysethi.victronbleexporter.tunnel.CloudflaredManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startExporterService()
        } else {
            Toast.makeText(this, "Some permissions denied. BLE may not work.", Toast.LENGTH_LONG).show()
            // Still start service to show discovery of devices that don't need location? Start anyway for UX
            startExporterService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                VictronBleExporterScreen(
                    onStart = { startExporterService() },
                    onStop = { stopExporterService() },
                    onAddKey = { mac, key -> addKeyToService(mac, key) },
                    onRemoveKey = { mac -> removeKey(mac) },
                    onChargerSet = { mac, enable -> sendChargerSet(mac, enable) },
                    onChargerRead = { mac -> sendChargerRead(mac) },
                    onChargerScheduleSave = { mac, enabled, enableTime, disableTime ->
                        saveChargerSchedule(mac, enabled, enableTime, disableTime)
                    },
                    onStartTunnel = { token -> startTunnel(token) },
                    onQuickTunnel = { startQuickTunnel() },
                    onStopTunnel = { stopTunnel() },
                    onShareDebugLogs = { shareDebugLogs() },
                    onCopyDebugLog = { copyDebugLog() },
                    onCopyTunnelUrl = { url -> copyTunnelUrl(url) },
                    onShareTunnelUrl = { url -> shareTunnelUrl(url) },
                    onDnsSelfTest = { runDnsSelfTest() },
                    onDisableBatteryOpt = { requestDisableBatteryOptimizations() },
                    onCheckUpdates = { checkForUpdates() },
                    onSendDiagnostics = { sendDiagnostics() },
                    onDownloadUpdate = { url -> downloadUpdate(url) }
                )
            }
        }

        checkAndRequestPermissions()

        // Remote diagnostics: fire-and-forget auto-send (rate-limited to 1/hour) and a
        // silent update check that only surfaces a banner when a newer APK is served.
        AppLog.init(this)
        AppLog.i("App opened — app v${BuildConfig.VERSION_NAME}")
        Diagnostics.autoSend(applicationContext)
        lifecycleScope.launch { runUpdateCheck(showResult = false) }
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Legacy for Android 8-11
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            try {
                permissionsLauncher.launch(missing.toTypedArray())
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Permission request failed", e)
            }
        } else {
            // All permissions already granted - auto-start scanning service for convenience
            startExporterService()
        }
    }

    private fun startExporterService() {
        try {
            val intent = Intent(this, VictronBleExporterService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Scanning for Victron devices...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopExporterService() {
        try {
            val intent = Intent(this, VictronBleExporterService::class.java)
            stopService(intent)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addKeyToService(mac: String, key: String) {
        try {
            val normalizedMac = DeviceRepository.normalizeMacInput(mac)
            val cleanKey = DeviceRepository.normalizeKeyInput(key)

            if (!DeviceRepository.isValidMac(normalizedMac)) {
                Toast.makeText(this, "Invalid MAC format. Use AA:BB:CC:DD:EE:FF", Toast.LENGTH_LONG).show()
                return
            }
            if (!DeviceRepository.isValidKey(cleanKey)) {
                Toast.makeText(this, "Invalid key: need 32 hex chars (0-9, a-f). Got ${cleanKey.length}", Toast.LENGTH_LONG).show()
                return
            }

            // Persist locally first (immediate UX)
            try {
                val repo = DeviceRepository(this)
                repo.saveDevice(normalizedMac, cleanKey)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Local persist failed", e)
            }

            val intent = Intent(this, VictronBleExporterService::class.java).apply {
                action = "ADD_KEY"
                putExtra("mac", normalizedMac)
                putExtra("key", cleanKey)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            DiscoveredDevicesStore.markHasKey(normalizedMac, true)
            Toast.makeText(this, "Key saved for $normalizedMac", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Add key failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeKey(mac: String) {
        try {
            val repo = DeviceRepository(this)
            repo.removeDevice(mac)
            Toast.makeText(this, "Removed key for $mac", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Remove failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendToService(intent: Intent) {
        // minSdk 26: startForegroundService is always available.
        startForegroundService(intent)
    }

    private fun sendChargerSet(mac: String, enable: Boolean) {
        if (mac.isBlank()) {
            Toast.makeText(this, "Enter the charger device MAC first", Toast.LENGTH_SHORT).show()
            return
        }
        sendToService(
            Intent(this, VictronBleExporterService::class.java).apply {
                action = "CHARGER_SET"
                putExtra("mac", mac.trim().uppercase())
                putExtra("enable", enable)
            }
        )
        Toast.makeText(this, if (enable) "Enabling charger…" else "Disabling charger…", Toast.LENGTH_SHORT).show()
    }

    private fun sendChargerRead(mac: String) {
        if (mac.isBlank()) {
            Toast.makeText(this, "Enter the charger device MAC first", Toast.LENGTH_SHORT).show()
            return
        }
        sendToService(
            Intent(this, VictronBleExporterService::class.java).apply {
                action = "CHARGER_READ"
                putExtra("mac", mac.trim().uppercase())
            }
        )
        Toast.makeText(this, "Reading charger state…", Toast.LENGTH_SHORT).show()
    }

    private fun saveChargerSchedule(mac: String, enabled: Boolean, enableTime: String, disableTime: String) {
        if (mac.isBlank()) {
            Toast.makeText(this, "Enter the charger device MAC first", Toast.LENGTH_SHORT).show()
            return
        }
        sendToService(
            Intent(this, VictronBleExporterService::class.java).apply {
                action = "CHARGER_SCHEDULE_SAVE"
                putExtra("mac", mac.trim().uppercase())
                putExtra("schedule_enabled", enabled)
                putExtra("enable_time", enableTime)
                putExtra("disable_time", disableTime)
            }
        )
        Toast.makeText(this, "Schedule saved", Toast.LENGTH_SHORT).show()
    }

    private fun startTunnel(token: String) {
        // Persist so the named tunnel can be restored after app/service restart or reboot.
        try {
            DeviceRepository(this).saveTunnelToken(token)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to persist tunnel token", e)
        }
        val intent = Intent(this, VictronBleExporterService::class.java).apply {
            action = "START_TUNNEL"
            putExtra("tunnel_token", token)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startQuickTunnel() {
        val intent = Intent(this, VictronBleExporterService::class.java).apply {
            action = "START_TUNNEL"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTunnel() {
        val intent = Intent(this, VictronBleExporterService::class.java).apply {
            action = "STOP_TUNNEL"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun shareDebugLogs() {
        val log = AppState.cloudflaredManager?.buildDebugLog()
        val full = if (log == null) {
            Toast.makeText(this, "Tunnel service not started yet", Toast.LENGTH_SHORT).show()
            return
        } else {
            log + "\n\n" + AppState.chargerDebugSection()
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Victron cloudflared debug log")
            putExtra(Intent.EXTRA_TEXT, full)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, "Share debug logs"))
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Share sheet unavailable", e)
            copyTextToClipboard(full)
            Toast.makeText(this, "Share unavailable — debug log copied to clipboard", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyDebugLog() {
        val log = AppState.cloudflaredManager?.buildDebugLog()
        if (log == null) {
            Toast.makeText(this, "Tunnel service not started yet", Toast.LENGTH_SHORT).show()
            return
        }
        copyTextToClipboard(log + "\n\n" + AppState.chargerDebugSection())
        Toast.makeText(this, "Debug log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun copyTunnelUrl(url: String) {
        copyTextToClipboard(url, "victron-tunnel-url")
        Toast.makeText(this, "Tunnel URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareTunnelUrl(url: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Victron MPPT tunnel URL")
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, "Share tunnel URL"))
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Share sheet unavailable", e)
            copyTextToClipboard(url, "victron-tunnel-url")
            Toast.makeText(this, "Share unavailable — tunnel URL copied to clipboard", Toast.LENGTH_LONG).show()
        }
    }

    private fun runDnsSelfTest() {
        // Ensure a manager exists even if the exporter service has not been started yet.
        val manager = AppState.cloudflaredManager ?: CloudflaredManager(applicationContext)
        Toast.makeText(this, "Running DNS self-test…", Toast.LENGTH_SHORT).show()
        manager.runDnsSelfTest { result ->
            val firstLine = result.lineSequence().firstOrNull().orEmpty()
            Toast.makeText(this, firstLine.ifBlank { "DNS self-test finished" }, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyTextToClipboard(text: String, label: String = "victron-debug-log") {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Clipboard copy failed", e)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimizations already disabled", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open battery settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Remote diagnostics + in-app updates ----

    private fun checkForUpdates() = runUpdateCheck(showResult = true)

    /**
     * Fetch latest.json and update AppState. Silent (no message) on app start;
     * the manual "Check for updates" path shows an outcome message.
     */
    private fun runUpdateCheck(showResult: Boolean) {
        AppState.updateChecking = true
        if (showResult) AppState.updateCheckMessage = null
        lifecycleScope.launch {
            try {
                val latest = UpdateChecker.fetchLatest()
                if (latest != null) {
                    AppState.updateVersionName = latest.versionName
                    AppState.updateNotes = latest.notes
                    AppState.updateApkUrl = latest.apkUrl
                    if (UpdateChecker.isNewer(latest.versionCode, BuildConfig.VERSION_CODE)) {
                        AppState.updateAvailable = true
                        if (showResult) {
                            AppState.updateCheckMessage = "Update available (v${latest.versionName}) — tap Download to install."
                        }
                    } else {
                        AppState.updateAvailable = false
                        if (showResult) {
                            AppState.updateCheckMessage = "You're on the latest version (v${BuildConfig.VERSION_NAME})."
                        }
                    }
                } else if (showResult) {
                    AppState.updateCheckMessage = "Update check failed — server unreachable. Try again later."
                }
            } finally {
                // Always clear the in-flight flag, even when the coroutine is
                // cancelled by activity destruction mid-check.
                AppState.updateChecking = false
            }
        }
    }

    private fun sendDiagnostics() {
        AppState.diagnosticsSending = true
        AppState.diagnosticsResult = null
        lifecycleScope.launch {
            try {
                val result = Diagnostics.sendLogs(applicationContext)
                AppState.diagnosticsResult = result.fold(
                    onSuccess = { "Diagnostics sent ✓ (${Diagnostics.currentEntries().size} entries)" },
                    onFailure = { "Send failed: ${it.message ?: "no connection"} — logs kept locally." }
                )
                Toast.makeText(this@MainActivity, AppState.diagnosticsResult, Toast.LENGTH_SHORT).show()
            } finally {
                // try/finally so the button never sticks on "Sending…" even when the
                // activity is destroyed mid-send and the coroutine is cancelled.
                AppState.diagnosticsSending = false
            }
        }
    }

    private fun downloadUpdate(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open $url", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun VictronBleExporterScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddKey: (String, String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onChargerSet: (String, Boolean) -> Unit,
    onChargerRead: (String) -> Unit,
    onChargerScheduleSave: (String, Boolean, String, String) -> Unit,
    onStartTunnel: (String) -> Unit,
    onQuickTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onShareDebugLogs: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onCopyTunnelUrl: (String) -> Unit,
    onShareTunnelUrl: (String) -> Unit,
    onDnsSelfTest: () -> Unit,
    onDisableBatteryOpt: () -> Unit,
    onCheckUpdates: () -> Unit,
    onSendDiagnostics: () -> Unit,
    onDownloadUpdate: (String) -> Unit
) {
    val context = LocalContext.current
    var deviceCount by remember { mutableStateOf(0) }
    var liveDevices by remember { mutableStateOf(emptyList<Pair<String, Map<String, Any?>>>()) }
    var discoveredDevices by remember { mutableStateOf(emptyList<DiscoveredDevice>()) }
    var savedKeys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var macInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var tunnelToken by remember { mutableStateOf("") }

    // Charger control UI state
    var chargerMac by remember { mutableStateOf("") }
    var chargerModeText by remember { mutableStateOf(AppState.chargerModeText) }
    var chargerBusy by remember { mutableStateOf(AppState.chargerBusy) }
    var chargerLastAction by remember { mutableStateOf(AppState.chargerLastAction) }
    var chargerLastError by remember { mutableStateOf(AppState.chargerLastError) }
    var chargerOverrideUntil by remember { mutableLongStateOf(AppState.chargerOverrideUntil) }
    var scheduleEnabled by remember { mutableStateOf(false) }
    var enableTime by remember { mutableStateOf("08:30") }
    var disableTime by remember { mutableStateOf("18:00") }
    var scheduleLoaded by remember { mutableStateOf(false) }

    var localIp by remember { mutableStateOf("Unknown IP") }
    var tunnelStatus by remember { mutableStateOf(AppState.tunnelStatus) }
    var tunnelUrl by remember { mutableStateOf(AppState.tunnelUrl) }
    var dnsSelfTestResult by remember { mutableStateOf(AppState.dnsSelfTestResult) }
    var isScanning by remember { mutableStateOf(false) }

    // Diagnostics + update-check UI state (polled from AppState like the rest)
    // App version footer — same packageManager source CloudflaredManager uses for the debug log.
    val versionText = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            "v${info.versionName ?: "?"} (build $code)"
        } catch (e: Exception) {
            "v?"
        }
    }

    var updateChecking by remember { mutableStateOf(AppState.updateChecking) }
    var updateAvailable by remember { mutableStateOf(AppState.updateAvailable) }
    var updateVersionName by remember { mutableStateOf(AppState.updateVersionName) }
    var updateNotes by remember { mutableStateOf(AppState.updateNotes) }
    var updateApkUrl by remember { mutableStateOf(AppState.updateApkUrl) }
    var updateCheckMessage by remember { mutableStateOf(AppState.updateCheckMessage) }
    var diagnosticsSending by remember { mutableStateOf(AppState.diagnosticsSending) }
    var diagnosticsResult by remember { mutableStateOf(AppState.diagnosticsResult) }

    // Clipboard helpers
    fun pasteFromClipboard(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun copyToClipboard(text: String, label: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Clipboard copy failed", e)
        }
    }

    // Local BLE discovery in UI for instant feedback, even before service starts
    DisposableEffect(Unit) {
        var scanCallback: ScanCallback? = null
        var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter
            if (adapter != null && adapter.isEnabled) {
                scanner = adapter.bluetoothLeScanner
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                val filter = ScanFilter.Builder()
                    .setManufacturerData(0x02E1, byteArrayOf(0x10))
                    .build()

                scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        try {
                            val mac = result.device.address.uppercase()
                            val rssi = result.rssi
                            val mfg = result.scanRecord?.getManufacturerSpecificData(0x02E1) ?: return
                            val modelId = if (mfg.size >= 4) ((mfg[3].toInt() and 0xFF) shl 8) or (mfg[2].toInt() and 0xFF) else null
                            val recordType = if (mfg.size >= 5) mfg[4].toInt() and 0xFF else null
                            // Check if we already have a key persisted
                            val repo = try { DeviceRepository(context) } catch (e: Exception) { null }
                            val hasKey = repo?.hasKey(mac) == true

                            DiscoveredDevicesStore.updateSeen(
                                mac = mac,
                                modelId = modelId,
                                recordType = recordType,
                                rssi = rssi,
                                hasKey = hasKey,
                                parsed = null
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Local scan result error", e)
                        }
                    }
                }

                try {
                    scanner?.startScan(listOf(filter), settings, scanCallback)
                    isScanning = true
                } catch (se: SecurityException) {
                    android.util.Log.w("MainActivity", "Missing BT permission for local scan", se)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Local scanner init failed", e)
        }

        onDispose {
            try {
                if (scanCallback != null && scanner != null &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                ) {
                    scanner.stopScan(scanCallback)
                }
            } catch (e: Exception) {
                // ignore
            }
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        // Fetch WiFi IP safely
        try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipAddress = try {
                @Suppress("DEPRECATION")
                wifiMgr?.connectionInfo?.ipAddress ?: 0
            } catch (_: Exception) { 0 }
            if (ipAddress != 0) {
                localIp = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            } else {
                localIp = "127.0.0.1"
            }
        } catch (e: Exception) {
            localIp = "Unknown"
        }

        while (true) {
            try {
                // Live parsed devices
                val all = MetricsStore.getAll()
                deviceCount = all.size
                liveDevices = all.map { (mac, parsed) -> mac to parsed.data }

                // Nearby discovered devices (even without keys)
                discoveredDevices = DiscoveredDevicesStore.getSortedByRssi()

                // Saved keys
                try {
                    val repo = DeviceRepository(context)
                    savedKeys = repo.getAllDevices()
                } catch (e: Exception) {
                    // ignore
                }

                tunnelStatus = AppState.tunnelStatus
                // Auto-copy the tunnel URL the moment it appears (or reappears after a
                // stop/restart with a new URL) so it is instantly shareable — even right
                // after a fresh app start. AppState-scoped marker avoids re-toasting the
                // same URL on rotation/recomposition.
                val url = AppState.tunnelUrl
                if (url == null) {
                    AppState.lastAutoCopiedTunnelUrl = null
                } else if (url != AppState.lastAutoCopiedTunnelUrl) {
                    AppState.lastAutoCopiedTunnelUrl = url
                    copyToClipboard(url, "victron-tunnel-url")
                    Toast.makeText(context, "Tunnel URL copied", Toast.LENGTH_SHORT).show()
                }
                tunnelUrl = url
                dnsSelfTestResult = AppState.dnsSelfTestResult

                // Charger control state (driven by the foreground service)
                chargerModeText = AppState.chargerModeText
                chargerBusy = AppState.chargerBusy
                chargerLastAction = AppState.chargerLastAction
                chargerLastError = AppState.chargerLastError
                chargerOverrideUntil = AppState.chargerOverrideUntil

                // Diagnostics + update-check state
                updateChecking = AppState.updateChecking
                updateAvailable = AppState.updateAvailable
                updateVersionName = AppState.updateVersionName
                updateNotes = AppState.updateNotes
                updateApkUrl = AppState.updateApkUrl
                updateCheckMessage = AppState.updateCheckMessage
                diagnosticsSending = AppState.diagnosticsSending
                diagnosticsResult = AppState.diagnosticsResult
                if (AppState.chargerMac != null && chargerMac.isBlank()) {
                    chargerMac = AppState.chargerMac!!
                }
                if (chargerMac.isBlank() && savedKeys.isNotEmpty()) {
                    chargerMac = savedKeys.keys.first()
                }
                // Load persisted schedule settings once
                if (!scheduleLoaded) {
                    try {
                        val store = com.lakshaysethi.victronbleexporter.data.ChargerScheduleStore(context)
                        scheduleEnabled = store.scheduleEnabled
                        enableTime = store.enableTime
                        disableTime = store.disableTime
                        if (store.chargerMac.isNotBlank()) chargerMac = store.chargerMac
                        scheduleLoaded = true
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "UI loop error", e)
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Victron BLE Exporter",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Easy MPPT discovery • Auto-scan • Saves keys",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // Setup card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Keep-Alive Setup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("For 24/7 running with screen off, disable battery optimizations.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Button(onClick = onDisableBatteryOpt) {
                    Text("Disable Battery Optimizations")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Service control + scanning status
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onStart() }, modifier = Modifier.weight(1f)) {
                Text("Start Scan")
            }
            OutlinedButton(onClick = { onStop() }, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val scanningText = if (discoveredDevices.isNotEmpty() || isScanning) "Scanning • ${discoveredDevices.size} nearby" else "Idle - tap Start Scan"
            Text(
                scanningText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isScanning) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Local Metrics:", style = MaterialTheme.typography.titleSmall)
        Text("http://$localIp:5338/metrics", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Text("http://$localIp:5338/devices (JSON)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ===== NEARBY DEVICES - THE CORE EASY UX =====
        Text("Nearby Victron Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Tap a device to auto-fill MAC. VictronConnect not needed for MAC - only for the 32-char key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (discoveredDevices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No Victron devices seen yet", fontWeight = FontWeight.Bold)
                    Text("• Make sure Bluetooth is ON", style = MaterialTheme.typography.bodySmall)
                    Text("• Keep MPPT within 5m", style = MaterialTheme.typography.bodySmall)
                    Text("• MPPT must have Instant Readout enabled (via VictronConnect once)", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("MPPT focus: we auto-detect BlueSolar / SmartSolar MPPTs", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                discoveredDevices.forEach { dev ->
                    val isMppt = dev.modelName.contains("MPPT", ignoreCase = true) || dev.recordType == 0x01
                    val rssiStrength = when {
                        dev.rssi > -60 -> "Strong"
                        dev.rssi > -75 -> "Good"
                        dev.rssi > -85 -> "Weak"
                        else -> "Very weak"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = if (isMppt) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(
                            containerColor = if (dev.hasKey && dev.parsed != null)
                                MaterialTheme.colorScheme.primaryContainer
                            else if (dev.hasKey)
                                MaterialTheme.colorScheme.secondaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        ),
                        onClick = {
                            macInput = dev.mac
                            // Haptic? Toast hint
                            Toast.makeText(context, "Selected ${dev.modelName} - now paste key", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        dev.modelName + if (isMppt) " • MPPT" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        dev.mac,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                    Badge(
                                        containerColor = when {
                                            dev.hasKey && dev.parsed != null -> MaterialTheme.colorScheme.primary
                                            dev.hasKey -> MaterialTheme.colorScheme.secondary
                                            else -> MaterialTheme.colorScheme.error
                                        }
                                    ) {
                                        Text(
                                            when {
                                                dev.hasKey && dev.parsed != null -> "Ready ✓"
                                                dev.hasKey -> "Key saved"
                                                else -> "Needs key"
                                            },
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("${dev.rssi} dBm • $rssiStrength", style = MaterialTheme.typography.labelSmall)
                                    Text(DiscoveredDevicesStore.timeAgo(dev.lastSeenTimestamp), style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            // Quick preview of parsed data if available
                            dev.parsed?.let { parsed ->
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                val data = parsed.data
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    data["battery_voltage"]?.let { Text("Batt: ${it}V", style = MaterialTheme.typography.bodySmall) }
                                    data["solar_power_w"]?.let { Text("${it}W", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                                    data["yield_today_wh"]?.let { Text("${it}Wh today", style = MaterialTheme.typography.bodySmall) }
                                }
                            }

                            if (dev.needsKey) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { macInput = dev.mac },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Select & Add Key")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val pasted = pasteFromClipboard()
                                            val clean = DeviceRepository.normalizeKeyInput(pasted)
                                            if (DeviceRepository.isValidKey(clean)) {
                                                keyInput = clean
                                                macInput = dev.mac
                                                Toast.makeText(context, "Pasted key from clipboard", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Clipboard doesn't contain 32-char hex key", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Text("Paste Key")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ===== ADD KEY SECTION - IMPROVED =====
        Text("Add / Edit Device Key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "How to get key: VictronConnect → Device → Settings (gear) → Product info → Instant readout → Show encryption key (32 hex chars). Copy once, saved forever.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = macInput,
            onValueChange = { macInput = DeviceRepository.normalizeMacInput(it) },
            label = { Text("Device MAC (auto-filled from scan)") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = macInput.isNotBlank() && !DeviceRepository.isValidMac(macInput),
            supportingText = {
                if (macInput.isNotBlank() && !DeviceRepository.isValidMac(macInput)) {
                    Text("Invalid MAC, should be AA:BB:CC:DD:EE:FF")
                } else if (macInput.isNotBlank()) {
                    Text("✓ Valid MAC", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = keyInput,
            onValueChange = {
                // Auto-clean as user types: allow pasting with spaces, colons, etc.
                val clean = if (it.length > 32) DeviceRepository.normalizeKeyInput(it) else it.lowercase().replace(Regex("[^0-9a-fA-F]"), "")
                keyInput = clean.take(32).lowercase()
            },
            label = { Text("32-char hex encryption key") },
            placeholder = { Text("a1b2c3d4e5f6... (32 hex)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 1,
            isError = keyInput.isNotBlank() && keyInput.length != 32,
            supportingText = {
                Text("${keyInput.length}/32 chars ${if (DeviceRepository.isValidKey(keyInput)) "✓ valid" else ""}")
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val normMac = DeviceRepository.normalizeMacInput(macInput)
                    val normKey = DeviceRepository.normalizeKeyInput(keyInput)
                    if (DeviceRepository.isValidMac(normMac) && DeviceRepository.isValidKey(normKey)) {
                        onAddKey(normMac, normKey)
                        keyInput = ""
                        // keep macInput for convenience or clear? Keep for feedback
                    } else {
                        Toast.makeText(context, "Fix MAC and key first", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = DeviceRepository.isValidMac(macInput) && DeviceRepository.isValidKey(keyInput)
            ) {
                Text("Save Key")
            }
            OutlinedButton(
                onClick = {
                    val pasted = pasteFromClipboard()
                    val clean = DeviceRepository.normalizeKeyInput(pasted)
                    if (clean.isNotBlank()) {
                        if (clean.length == 32) {
                            keyInput = clean
                        } else {
                            // Try to find 32-char hex inside clipboard
                            val match = Regex("[0-9a-fA-F]{32}").find(pasted)
                            if (match != null) {
                                keyInput = match.value.lowercase()
                            } else {
                                Toast.makeText(context, "Clipboard: $clean (${clean.length} chars) not 32", Toast.LENGTH_SHORT).show()
                                keyInput = clean.take(32)
                            }
                        }
                    } else {
                        Toast.makeText(context, "Clipboard empty", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("Paste")
            }
            OutlinedButton(onClick = { macInput = ""; keyInput = "" }) {
                Text("Clear")
            }
        }

        // Saved keys list
        if (savedKeys.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Saved Keys (${savedKeys.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                savedKeys.forEach { (mac, key) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mac, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("••••${key.takeLast(4)} (saved)", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    macInput = mac
                                    keyInput = key
                                }) {
                                    Text("Edit")
                                }
                                TextButton(
                                    onClick = { onRemoveKey(mac) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Live metrics from parsed devices
        Text("Live Metrics ($deviceCount MPPT / Shunt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (liveDevices.isEmpty()) {
            Text(
                "No decrypted data yet. Add key for a discovered device above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                liveDevices.forEach { (mac, data) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(mac, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(4.dp))
                            data.forEach { (k, v) ->
                                Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ===== CHARGER CONTROL (enable/disable over BLE + daily schedule) =====
        Text("Charger Control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Enable/disable the MPPT charger over BLE. First connection prompts for pairing — PIN is usually 000000 (or on the product sticker).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = chargerMac,
            onValueChange = { chargerMac = it.uppercase() },
            label = { Text("Charger device MAC") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text("Auto-filled from your saved devices — the MPPT's MAC")
            }
        )
        Spacer(Modifier.height(8.dp))

        // Current state + last action
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (chargerModeText) {
                    "ON" -> MaterialTheme.colorScheme.primaryContainer
                    "OFF" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "Charger: $chargerModeText",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (chargerBusy) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
                Text(chargerLastAction, style = MaterialTheme.typography.bodySmall)
                if (chargerOverrideUntil > System.currentTimeMillis()) {
                    val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        .format(java.util.Date(chargerOverrideUntil))
                    Text(
                        "Manual override — schedule paused until $until",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                chargerLastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onChargerSet(chargerMac, true) },
                modifier = Modifier.weight(1f),
                enabled = !chargerBusy && chargerMac.isNotBlank()
            ) {
                Text("Enable Charger")
            }
            OutlinedButton(
                onClick = { onChargerSet(chargerMac, false) },
                modifier = Modifier.weight(1f),
                enabled = !chargerBusy && chargerMac.isNotBlank()
            ) {
                Text("Disable Charger")
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { onChargerRead(chargerMac) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !chargerBusy && chargerMac.isNotBlank()
        ) {
            Text("Read Current State")
        }

        Spacer(Modifier.height(12.dp))

        // Schedule card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Daily schedule", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Charger ON from enable time, OFF from disable time.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = scheduleEnabled, onCheckedChange = { scheduleEnabled = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TimePickerButton(
                        current = enableTime,
                        defaultHour = 8,
                        defaultMinute = 30,
                        onPicked = { enableTime = it },
                        modifier = Modifier.weight(1f)
                    ) { Text("ON at $it") }
                    TimePickerButton(
                        current = disableTime,
                        defaultHour = 18,
                        defaultMinute = 0,
                        onPicked = { disableTime = it },
                        modifier = Modifier.weight(1f)
                    ) { Text("OFF at $it") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onChargerScheduleSave(chargerMac, scheduleEnabled, enableTime, disableTime) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = chargerMac.isNotBlank() &&
                        com.lakshaysethi.victronbleexporter.charger.ChargerSchedule.isValidTime(enableTime) &&
                        com.lakshaysethi.victronbleexporter.charger.ChargerSchedule.isValidTime(disableTime)
                ) {
                    Text("Save Schedule")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Note: the schedule applies while the app is open (foreground service running). " +
                        "Manual Enable/Disable pauses the schedule until the next window boundary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Cloudflare Tunnel (optional)", style = MaterialTheme.typography.titleMedium)
        Text("Expose metrics to internet", style = MaterialTheme.typography.bodySmall)
        Text("Tunnel: $tunnelStatus", style = MaterialTheme.typography.bodyMedium)
        tunnelUrl?.let { url ->
            SelectionContainer {
                Text("Public URL: $url", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onShareTunnelUrl(url) }, modifier = Modifier.weight(1f)) {
                    Text("Share URL")
                }
                OutlinedButton(onClick = { onCopyTunnelUrl(url) }, modifier = Modifier.weight(1f)) {
                    Text("Copy URL")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = tunnelToken,
            onValueChange = { tunnelToken = it },
            label = { Text("Named Tunnel Token (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { if (tunnelToken.isNotBlank()) onStartTunnel(tunnelToken) }) {
                Text("Start Named")
            }
            OutlinedButton(onClick = onQuickTunnel) {
                Text("Quick Tunnel")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStopTunnel, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Disable/Stop Cloudflared")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShareDebugLogs, modifier = Modifier.weight(1f)) {
                Text("Share Debug Logs")
            }
            OutlinedButton(onClick = onCopyDebugLog, modifier = Modifier.weight(1f)) {
                Text("Copy Log")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDnsSelfTest, modifier = Modifier.fillMaxWidth()) {
            Text("DNS Self-Test")
        }
        dnsSelfTestResult?.let { report ->
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "DNS / network self-test",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ===== DIAGNOSTICS & UPDATES =====
        Text("Diagnostics & Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Sends the last ${AppLog.MAX_ENTRIES} app/charger log entries plus device info to the " +
                "captain's server. Auto-sends on app start and after significant errors (max once/hour); " +
                "the button below sends immediately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (updateAvailable) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Update available (v${updateVersionName ?: ""})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    updateNotes?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onDownloadUpdate(updateApkUrl ?: UpdateChecker.DEFAULT_APK_URL) }) {
                        Text("Download & Install")
                    }
                    Text(
                        "Opens the APK in your browser for download/install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCheckUpdates,
                modifier = Modifier.weight(1f),
                enabled = !updateChecking
            ) {
                Text(if (updateChecking) "Checking…" else "Check for Updates")
            }
            Button(
                onClick = onSendDiagnostics,
                modifier = Modifier.weight(1f),
                enabled = !diagnosticsSending
            ) {
                Text(if (diagnosticsSending) "Sending…" else "Send Diagnostics")
            }
        }
        updateCheckMessage?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        diagnosticsResult?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            versionText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Button that opens a platform time picker and reports "HH:mm".
 * The dialog is created inside a LaunchedEffect so it is shown exactly once per
 * open (a plain `if` would re-show it on every recomposition, stacking dialogs).
 */
@Composable
fun TimePickerButton(
    current: String,
    defaultHour: Int,
    defaultMinute: Int,
    onPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        label(current)
    }
    if (open) {
        val parts = current.split(":").map { it.toIntOrNull() ?: 0 }
        val hour = parts.getOrElse(0) { defaultHour }.coerceIn(0, 23)
        val minute = parts.getOrElse(1) { defaultMinute }.coerceIn(0, 59)
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            val dialog = android.app.TimePickerDialog(
                context,
                { _, h, m ->
                    onPicked("%02d:%02d".format(h, m))
                    open = false
                },
                hour,
                minute,
                true
            )
            dialog.setOnDismissListener { open = false }
            dialog.show()
        }
    }
}
