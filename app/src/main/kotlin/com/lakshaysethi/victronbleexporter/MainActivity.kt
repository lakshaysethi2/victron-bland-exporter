package com.lakshaysethi.victronbleexporter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lakshaysethi.victronbleexporter.exporter.MetricsStore
import com.lakshaysethi.victronbleexporter.parser.VictronParser
import com.lakshaysethi.victronbleexporter.service.VictronBleExporterService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                    onQuickTunnel = { startQuickTunnel() }
                )
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE
        )

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        } else {
            // Already granted
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
        startForegroundService(intent)
    }

    private fun startQuickTunnel() {
        val intent = Intent(this, VictronBleExporterService::class.java).apply {
            action = "START_TUNNEL"
            // no token => quick tunnel
        }
        startForegroundService(intent)
    }
}

@Composable
fun VictronBleExporterScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddKey: (String, String) -> Unit,
    onStartTunnel: (String) -> Unit,
    onQuickTunnel: () -> Unit
) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var tunnelStatus by remember { mutableStateOf("Stopped") }
    var tunnelUrl by remember { mutableStateOf<String?>(null) }
    var deviceCount by remember { mutableStateOf(0) }
    var devices by remember { mutableStateOf(emptyList<Pair<String, Map<String, Any?>>>()) }

    var macInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var tunnelToken by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // Live refresh loop
    LaunchedEffect(Unit) {
        while (true) {
            val all = MetricsStore.getAll()
            deviceCount = all.size
            devices = all.map { (mac, parsed) ->
                mac to parsed.data
            }
            // Update UI tunnel info (we would need to expose from service, for demo we fake)
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Victron BLE Prometheus Exporter",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))

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
                Text("Stop")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Status: ${if (isRunning) "Running" else "Stopped"} | Devices: $deviceCount")
        Text("Tunnel: $tunnelStatus")
        tunnelUrl?.let {
            Text("Public URL: $it", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(24.dp))

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
        Row {
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
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                Toast.makeText(context, "Go to VictronConnect → Product Info → Instant Readout", Toast.LENGTH_LONG).show()
            }) {
                Text("How to get key")
            }
        }

        Spacer(Modifier.height(24.dp))

        // Cloudflare Tunnel
        Text("Cloudflare Tunnel", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = tunnelToken,
            onValueChange = { tunnelToken = it },
            label = { Text("Named Tunnel Token (from Cloudflare Zero Trust)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (tunnelToken.isNotBlank()) onStartTunnel(tunnelToken) }) {
                Text("Start Named Tunnel")
            }
            Button(onClick = onQuickTunnel) {
                Text("Quick Tunnel (trycloudflare)")
            }
            Button(onClick = { /* stop via intent not shown */ }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Stop Tunnel")
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("Live Devices", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { (mac, data) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(mac, style = MaterialTheme.typography.bodyMedium)
                        data.forEach { (k, v) ->
                            Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Metrics endpoint: http://localhost:9100/metrics\nUse Cloudflare tunnel to expose publicly.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}