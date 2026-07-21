package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private const val TAG = "CloudflaredManager"

/**
 * Manages the bundled cloudflared binary for Named or Quick tunnels.
 */
class CloudflaredManager(private val context: Context) {

    private var process: Process? = null
    var tunnelUrl: String? = null
        private set
    var status: String = "Stopped"
        private set

    private val binaryName = "libcloudflared.so"

    fun startNamedTunnel(token: String, onStatus: (String) -> Unit = {}) {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, binaryName)
        if (!bin.exists()) {
            status = "Error: cloudflared binary missing"
            onStatus(status)
            Log.e(TAG, "Missing $bin")
            return
        }

        try {
            bin.setExecutable(true)

            val pb = ProcessBuilder(
                bin.absolutePath,
                "tunnel", "run", "--token", token
            )
            pb.redirectErrorStream(true)
            process = pb.start()

            status = "Starting tunnel..."
            onStatus(status)

            // Read output in background for URL and status
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "cloudflared: $line")
                        when {
                            line!!.contains("Registered tunnel connection") -> {
                                status = "Connected"
                                onStatus(status)
                            }
                            line!!.contains("https://") && tunnelUrl == null -> {
                                val urlMatch = Regex("https://[^\s]+").find(line!!)
                                urlMatch?.let {
                                    tunnelUrl = it.value
                                    status = "Connected"
                                    onStatus(status)
                                }
                            }
                            line!!.contains("error", ignoreCase = true) -> {
                                status = "Error"
                                onStatus(status)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel reader error", e)
                }
            }.start()

            // Monitor exit
            Thread {
                val exit = process!!.waitFor()
                status = if (exit == 0) "Stopped" else "Crashed ($exit)"
                onStatus(status)
                Log.w(TAG, "cloudflared exited with code $exit")
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cloudflared", e)
            status = "Error: ${e.message}"
            onStatus(status)
        }
    }

    fun startQuickTunnel(localPort: Int = 9100, onStatus: (String) -> Unit = {}) {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, binaryName)
        if (!bin.exists()) {
            status = "Error: binary missing"
            onStatus(status)
            return
        }
        bin.setExecutable(true)

        try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "tunnel", "--url", "http://localhost:$localPort"
            )
            pb.redirectErrorStream(true)
            process = pb.start()

            status = "Starting quick tunnel..."
            onStatus(status)

            Thread {
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "cf: $line")
                    if (line!!.contains("https://") && tunnelUrl == null) {
                        val match = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line!!)
                        match?.let {
                            tunnelUrl = it.value
                            status = "Connected (quick)"
                            onStatus(status)
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Quick tunnel fail", e)
            status = "Error"
            onStatus(status)
        }
    }

    fun stop() {
        process?.destroyForcibly()
        process = null
        tunnelUrl = null
        status = "Stopped"
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
