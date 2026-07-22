package com.lakshaysethi.victronbleexporter

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lakshaysethi.victronbleexporter.exporter.MetricsStore
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startExporterService()
        } else {
            Toast.makeText(this, "Some permissions denied. BLE will not work.", Toast.LENGTH_LONG).show()
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
                    onStartTunnel = { token -> startTunnel(token) },
                    onQuickTunnel = { startQuickTunnel() },
                    onStopTunnel = { stopTunnel() },
                    onDisableBatteryOpt = { requestDisableBatteryOptimizations() }
                )
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()

        // BT permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Location needed on < S and also for scan with neverForLocation flag removed
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Notifications only runtime on Android 13+
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
        }
    }

    private fun startExporterService() {
        val intent = Intent(this, VictronBleExporterService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Exporter service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopExporterService() {
        val intent = Intent(this, VictronBleExporterService::class.java)
        stopService(intent)
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun addKeyToService(mac: String, key: String) {
        val intent = Intent(this, VictronBleExporterService::class.java).apply {
            action = "ADD_KEY"
            putExtra("mac", mac)
            putExtra("key", key)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Key added for $mac", Toast.LENGTH_SHORT).show()
    }

    private fun startTunnel(token: String) {
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
            // no token => quick tunnel
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

    @SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimizations() {
        val intent = Intent()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Battery optimizations already disabled", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun VictronBleExporterScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddKey: (String, String) -> Unit,
    onStartTunnel: (String) -> Unit,
    onQuickTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onDisableBatteryOpt: () -> Unit
) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var deviceCount by remember { mutableStateOf(0) }
    var devices by remember { mutableStateOf(emptyList<Pair<String, Map<String, Any?>>>()) }

    var macInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var tunnelToken by remember { mutableStateOf("") }

    var localIp by remember { mutableStateOf("Unknown IP") }

    // Read AppState
    var tunnelStatus by remember { mutableStateOf(AppState.tunnelStatus) }
    var tunnelUrl by remember { mutableStateOf(AppState.tunnelUrl) }

    LaunchedEffect(Unit) {
        // Fetch WiFi IP - wrapped in try/catch to avoid crash on missing permission or deprecated API
        try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipAddress = try {
                @Suppress("DEPRECATION")
                wifiMgr?.connectionInfo?.ipAddress ?: 0
            } catch (se: SecurityException) {
                android.util.Log.w("MainActivity", "No ACCESS_WIFI_STATE permission for IP", se)
                0
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to get wifi IP", e)
                0
            }
            if (ipAddress != 0) {
                val ipStr = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
                localIp = ipStr
            } else {
                localIp = "127.0.0.1 (no wifi)"
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "IP fetch failed", e)
            localIp = "Unknown (err: ${e.message})"
        }

        while (true) {
            try {
                val all = MetricsStore.getAll()
                deviceCount = all.size
                devices = all.map { (mac, parsed) ->
                    mac to parsed.data
                }
                tunnelStatus = AppState.tunnelStatus
                tunnelUrl = AppState.tunnelUrl
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Loop update failed", e)
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
            "Victron BLE Prometheus Exporter",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))

        Card {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("App Setup & Keep-Alive", style = MaterialTheme.typography.titleMedium)
                Text("To run constantly with screen off, disable battery optimizations.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Button(onClick = onDisableBatteryOpt) {
                    Text("Disable Battery Optimizations")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onStart()
                isRunning = true
            }) {
                Text("Start Exporter")
            }
            Button(onClick = {
                onStop()
                isRunning = false
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Stop Exporter")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text("Local Metrics Endpoint:", style = MaterialTheme.typography.titleSmall)
        Text("http://$localIp:5338/metrics", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Text("(Accessible over local Wi-Fi)", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Cloudflare Tunnel", style = MaterialTheme.typography.titleMedium)
        Text("Tunnel: $tunnelStatus", style = MaterialTheme.typography.bodyMedium)
        tunnelUrl?.let {
            Text("Public URL: $it", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
        
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = tunnelToken,
            onValueChange = { tunnelToken = it },
            label = { Text("Named Tunnel Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { if (tunnelToken.isNotBlank()) onStartTunnel(tunnelToken) }) {
                Text("Start Named")
            }
            Button(onClick = onQuickTunnel) {
                Text("Quick Tunnel")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStopTunnel, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Disable/Stop Cloudflared")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Add device key
        Text("Add Device Encryption Key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = macInput,
            onValueChange = { macInput = it },
            label = { Text("Device MAC (AA:BB:CC:DD:EE:FF)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("32-char hex encryption key") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.padding(top=8.dp)) {
            Button(onClick = {
                if (macInput.isNotBlank() && keyInput.length == 32) {
                    onAddKey(macInput.uppercase(), keyInput)
                    macInput = ""
                    keyInput = ""
                } else {
                    Toast.makeText(context, "Invalid MAC or key", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Add Key")
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("Live Devices ($deviceCount)", style = MaterialTheme.typography.titleMedium)
        // Using Column for list since we are inside a verticalScroll parent
        Column {
            devices.forEach { (mac, data) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(mac, style = MaterialTheme.typography.titleSmall)
                        data.forEach { (k, v) ->
                            Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
