package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

import com.lakshaysethi.victronbleexporter.AppState

private const val TAG = "CloudflaredManager"

/** Maximum number of cloudflared output lines kept for the debug log (ring buffer). */
internal const val TUNNEL_LOG_MAX_LINES = 200

/** Pure helpers so the ring buffer, arg construction and exit statuses are unit-testable on the JVM. */
internal object TunnelLog {
    fun append(buffer: ArrayDeque<String>, line: String) {
        buffer.addLast(line)
        while (buffer.size > TUNNEL_LOG_MAX_LINES) {
            buffer.removeFirst()
        }
    }

    fun snapshot(buffer: ArrayDeque<String>): List<String> = buffer.toList()

    fun lastLine(buffer: ArrayDeque<String>): String? = buffer.lastOrNull()

    fun exitStatus(exitCode: Int, lastLine: String?): String = when {
        exitCode == 0 -> "Stopped"
        lastLine.isNullOrBlank() -> "cloudflared exited (code $exitCode): (no output captured)"
        else -> "cloudflared exited (code $exitCode): ${lastLine.trim().take(200)}"
    }
}

internal object TunnelArgs {
    // --no-autoupdate: cloudflared's auto-updater tries to rewrite its own binary in
    // nativeLibraryDir, which is read-only on Android; that is a known exit-cause.
    const val NO_AUTOUPDATE = "--no-autoupdate"

    fun quickTunnel(localPort: Int): List<String> =
        listOf(NO_AUTOUPDATE, "tunnel", "--url", "http://localhost:$localPort")

    fun namedTunnel(token: String): List<String> =
        listOf(NO_AUTOUPDATE, "tunnel", "run", "--token", token)
}

class CloudflaredManager(private val context: Context) {

    private var process: Process? = null
    var tunnelUrl: String? = null
        private set
    var status: String = "Stopped"
        private set

    private val binaryName = "libcloudflared.so"

    // ---- debug instrumentation (exposed via buildDebugLog) ----
    private val logBuffer = ArrayDeque<String>()
    private val logBufferLock = Any()
    @Volatile private var lastExitCode: Int? = null
    @Volatile private var lastRunStartedAtMs: Long? = null
    @Volatile private var lastRunDurationMs: Long? = null
    private var lastCommand: List<String>? = null
    private var lastEnvOverrides: Map<String, String>? = null
    private var lastBinary: File? = null
    @Volatile private var manualStopRequested = false

    init {
        // Self-register so the UI can build a debug log without the service wiring it up.
        AppState.cloudflaredManager = this
    }

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
            val fullCommand = listOf(bin.absolutePath) + args
            val pb = ProcessBuilder(fullCommand)
            pb.redirectErrorStream(true)

            // Go binaries need a writable temp dir; cloudflared also reads its config
            // from HOME. The app's own dirs are the only guaranteed-writable locations.
            val env = pb.environment()
            val homeDir = context.filesDir.absolutePath
            val tmpDir = context.cacheDir.absolutePath
            env["HOME"] = homeDir
            env["TMPDIR"] = tmpDir
            env["TMP"] = tmpDir
            env["TEMP"] = tmpDir

            manualStopRequested = false
            lastExitCode = null
            lastRunStartedAtMs = System.currentTimeMillis()
            lastRunDurationMs = null
            lastCommand = fullCommand
            lastEnvOverrides = mapOf(
                "HOME" to homeDir,
                "TMPDIR" to tmpDir,
                "TMP" to tmpDir,
                "TEMP" to tmpDir
            )
            lastBinary = bin
            val proc = pb.start()
            process = proc

            setStatus("Starting tunnel...", onStatus)

            val readerThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        Log.d(TAG, "cloudflared: $text")
                        synchronized(logBufferLock) { TunnelLog.append(logBuffer, text) }
                        if (text.contains("Registered tunnel connection")) {
                            setStatus("Connected", onStatus)
                        } else if (text.contains("https://") && tunnelUrl == null) {
                            val match = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(text)
                            if (match != null) {
                                tunnelUrl = match.value
                                AppState.tunnelUrl = tunnelUrl
                                setStatus("Connected", onStatus)
                            }
                        } else if (text.contains("error", ignoreCase = true)) {
                            // Only update status if we aren't connected
                            if (status != "Connected") {
                                setStatus("cloudflared error: ${text.trim().take(200)}", onStatus)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel reader error", e)
                }
            }
            readerThread.start()

            Thread {
                val exit = proc.waitFor()
                // Give the reader a moment to drain buffered output before we snapshot the last line.
                try {
                    readerThread.join(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                lastExitCode = exit
                lastRunDurationMs = System.currentTimeMillis() - (lastRunStartedAtMs ?: System.currentTimeMillis())
                val lastLine = synchronized(logBufferLock) { TunnelLog.lastLine(logBuffer) }
                val finalStatus = if (manualStopRequested) {
                    "Stopped"
                } else {
                    TunnelLog.exitStatus(exit, lastLine)
                }
                setStatus(finalStatus, onStatus)
                Log.w(TAG, "cloudflared exited with code $exit after ${lastRunDurationMs}ms")
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
        prepareAndRun(TunnelArgs.namedTunnel(token), onStatus)
    }

    fun startQuickTunnel(localPort: Int = 5338, onStatus: (String) -> Unit = {}) {
        stop()
        prepareAndRun(TunnelArgs.quickTunnel(localPort), onStatus)
    }

    fun stop() {
        manualStopRequested = true
        process?.destroyForcibly()
        process = null
        tunnelUrl = null
        AppState.tunnelUrl = null
        status = "Stopped"
        AppState.tunnelStatus = status
    }

    fun isRunning(): Boolean = process?.isAlive == true

    // ---- debug log ----

    /**
     * Collects everything the crew needs to debug a failing tunnel into one
     * shareable text bundle: last cloudflared output lines, exit code + lifetime,
     * tunnel state, invocation args/env, and device info.
     */
    fun buildDebugLog(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Victron BLE Exporter — cloudflared debug log ===")
        sb.appendLine("Generated: ${timestamp()}")
        sb.appendLine()
        sb.appendLine("--- Device ---")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android SDK: API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("App version: ${appVersionName()} (code ${appVersionCode()})")
        sb.appendLine()
        sb.appendLine("--- Tunnel state ---")
        sb.appendLine("Manager status: $status")
        sb.appendLine("AppState.tunnelStatus: ${AppState.tunnelStatus}")
        sb.appendLine("AppState.tunnelUrl: ${AppState.tunnelUrl ?: "null"}")
        sb.appendLine("Process running: ${isRunning()}")
        sb.appendLine("Last exit code: ${lastExitCode?.toString() ?: "never exited"}")
        sb.appendLine("Last run duration: ${lastRunDurationMs?.let { "$it ms" } ?: "n/a"}")
        sb.appendLine("Cloudflared binary: ${lastBinary?.absolutePath ?: "n/a"} (${lastBinary?.length() ?: 0} bytes)")
        sb.appendLine()
        sb.appendLine("--- cloudflared invocation ---")
        sb.appendLine("Command: ${lastCommand?.joinToString(" ") ?: "n/a"}")
        sb.appendLine("Env overrides: ${lastEnvOverrides?.toSortedMap()?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: "n/a"}")
        sb.appendLine()
        sb.appendLine("--- last $TUNNEL_LOG_MAX_LINES cloudflared output lines ---")
        val lines = synchronized(logBufferLock) { TunnelLog.snapshot(logBuffer) }
        if (lines.isEmpty()) {
            sb.appendLine("(no output captured yet — run the tunnel first)")
        } else {
            lines.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun appVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun appVersionCode(): Long = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
    } catch (e: Exception) {
        -1L
    }
}
