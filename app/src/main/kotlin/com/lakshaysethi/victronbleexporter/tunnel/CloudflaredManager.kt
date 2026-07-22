package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URL
import kotlin.concurrent.thread

import com.lakshaysethi.victronbleexporter.AppState

private const val TAG = "CloudflaredManager"

class CloudflaredManager(private val context: Context) {

    private var process: Process? = null
    var tunnelUrl: String? = null
        private set
    var status: String = "Stopped"
        private set

    private val binaryName = "libcloudflared.so"
    private val expectedUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"

    private fun getBinaryFile(): File {
        // Try to use the downloaded one first
        val downloaded = File(context.filesDir, binaryName)
        if (downloaded.exists() && downloaded.length() > 1000000) {
            return downloaded
        }
        // Fallback to bundled
        return File(context.applicationInfo.nativeLibraryDir, binaryName)
    }

    private fun downloadCloudflared(onStatus: (String) -> Unit, onSuccess: () -> Unit) {
        thread {
            try {
                status = "Downloading cloudflared..."
                AppState.tunnelStatus = status
                onStatus(status)
                val target = File(context.filesDir, binaryName)
                URL(expectedUrl).openStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                target.setExecutable(true)
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download cloudflared", e)
                status = "Download Error: ${e.message}"
                AppState.tunnelStatus = status
                onStatus(status)
            }
        }
    }

    private fun prepareAndRun(args: List<String>, onStatus: (String) -> Unit) {
        val bin = getBinaryFile()
        if (!bin.exists() || bin.length() < 100000) { // If it's a dummy or missing
            downloadCloudflared(onStatus) {
                runBinary(getBinaryFile(), args, onStatus)
            }
        } else {
            runBinary(bin, args, onStatus)
        }
    }

    private fun runBinary(bin: File, args: List<String>, onStatus: (String) -> Unit) {
        try {
            bin.setExecutable(true)
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            pb.redirectErrorStream(true)
            process = pb.start()

            status = "Starting tunnel..."
            AppState.tunnelStatus = status
            onStatus(status)

            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "cloudflared: $line")
                        if (line!!.contains("Registered tunnel connection")) {
                            status = "Connected"
                            AppState.tunnelStatus = status
                            onStatus(status)
                        } else if (line!!.contains("https://") && tunnelUrl == null) {
                            val match = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line!!)
                            if (match != null) {
                                tunnelUrl = match.value
                                AppState.tunnelUrl = tunnelUrl
                                status = "Connected"
                                AppState.tunnelStatus = status
                                onStatus(status)
                            }
                        } else if (line!!.contains("error", ignoreCase = true)) {
                            // Only update status if we aren't connected
                            if (status != "Connected") {
                                status = "Error checking logs"
                                AppState.tunnelStatus = status
                                onStatus(status)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel reader error", e)
                }
            }.start()

            Thread {
                val exit = process!!.waitFor()
                status = if (exit == 0) "Stopped" else "Crashed ($exit)"
                AppState.tunnelStatus = status
                onStatus(status)
                Log.w(TAG, "cloudflared exited with code $exit")
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cloudflared", e)
            status = "Error: ${e.message}"
            AppState.tunnelStatus = status
            onStatus(status)
        }
    }

    fun startNamedTunnel(token: String, onStatus: (String) -> Unit = {}) {
        stop()
        prepareAndRun(listOf("tunnel", "run", "--token", token), onStatus)
    }

    fun startQuickTunnel(localPort: Int = 9100, onStatus: (String) -> Unit = {}) {
        stop()
        prepareAndRun(listOf("tunnel", "--url", "http://localhost:$localPort"), onStatus)
    }

    fun stop() {
        process?.destroyForcibly()
        process = null
        tunnelUrl = null
        AppState.tunnelUrl = null
        status = "Stopped"
        AppState.tunnelStatus = status
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
