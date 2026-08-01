package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

import com.lakshaysethi.victronbleexporter.AppState

private const val TAG = "CloudflaredManager"

class CloudflaredManager(private val context: Context) {

    private var process: Process? = null
    var tunnelUrl: String? = null
        private set
    var status: String = "Stopped"
        private set

    private val binaryName = "libcloudflared.so"

    /** The bundled cloudflared binary ships for arm64-v8a only. */
    private fun isSupportedAbi(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /**
     * cloudflared is bundled as a native library and executes from nativeLibraryDir,
     * the only location Android 10+ will exec from. App-private dirs (filesDir) are
     * mounted noexec, so a binary downloaded there can never run — setExecutable(true)
     * cannot override the mount flag. The old download-and-run path is therefore gone.
     */
    private fun getBinaryFile(): File? {
        if (!isSupportedAbi()) return null
        val bundled = File(context.applicationInfo.nativeLibraryDir, binaryName)
        return bundled.takeIf { it.exists() && it.length() > 100_000 }
    }

    private fun setStatus(value: String, onStatus: (String) -> Unit) {
        status = value
        AppState.tunnelStatus = value
        onStatus(value)
    }

    private fun prepareAndRun(args: List<String>, onStatus: (String) -> Unit) {
        val bin = getBinaryFile()
        if (bin == null) {
            val reason = if (isSupportedAbi()) {
                "cloudflared binary missing from native libs"
            } else {
                "cloudflared bundled for arm64 only — unsupported device ABI (${Build.SUPPORTED_ABIS.joinToString()})"
            }
            Log.e(TAG, reason)
            setStatus(reason, onStatus)
            return
        }
        runBinary(bin, args, onStatus)
    }

    private fun runBinary(bin: File, args: List<String>, onStatus: (String) -> Unit) {
        try {
            // nativeLibraryDir is extracted with exec permission; no chmod needed.
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            pb.redirectErrorStream(true)
            process = pb.start()

            setStatus("Starting tunnel...", onStatus)

            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "cloudflared: $line")
                        if (line!!.contains("Registered tunnel connection")) {
                            setStatus("Connected", onStatus)
                        } else if (line!!.contains("https://") && tunnelUrl == null) {
                            val match = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line!!)
                            if (match != null) {
                                tunnelUrl = match.value
                                AppState.tunnelUrl = tunnelUrl
                                setStatus("Connected", onStatus)
                            }
                        } else if (line!!.contains("error", ignoreCase = true)) {
                            if (status != "Connected") {
                                setStatus("cloudflared error: ${line!!.trim().take(200)}", onStatus)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel reader error", e)
                }
            }.start()

            Thread {
                val exit = process!!.waitFor()
                setStatus(
                    if (exit == 0) "Stopped"
                    else "cloudflared exited unexpectedly (code $exit)",
                    onStatus
                )
                Log.w(TAG, "cloudflared exited with code $exit")
            }.start()

        } catch (e: IOException) {
            Log.e(TAG, "Failed to start cloudflared", e)
            val hint = if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                " — device refused to exec the bundled binary"
            } else {
                ""
            }
            setStatus("Failed to start cloudflared: ${e.message ?: e.javaClass.simpleName}$hint", onStatus)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cloudflared", e)
            setStatus("Failed to start cloudflared: ${e.message ?: e.javaClass.simpleName}", onStatus)
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
