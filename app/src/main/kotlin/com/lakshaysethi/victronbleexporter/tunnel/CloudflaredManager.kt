package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Render an argv list for shareable logs with secrets scrubbed.
     * Redacts the value immediately following any `--token` flag.
     */
    fun redactCommand(args: List<String>?): String {
        if (args == null) return "n/a"
        if (args.isEmpty()) return ""
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            out.add(arg)
            if (arg == "--token") {
                if (i + 1 < args.size) {
                    out.add("<redacted>")
                    i += 2
                    continue
                }
            }
            i++
        }
        return out.joinToString(" ")
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

class CloudflaredManager(
    context: Context,
    /** Background executor for bind/DNS preflight + ProcessBuilder.start (never main). */
    private val startExecutor: Executor = defaultStartExecutor,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    // Application context only: AppState holds a static reference to this manager
    // so the UI can share debug logs even after the process died; never retain the
    // service/activity context here or the static field would leak it.
    private val appContext: Context = context.applicationContext

    @Volatile private var process: Process? = null
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
    private val runStateLock = Any()
    private val runGeneration = AtomicLong(0)

    // Last network bind + DNS preflight (surfaced in buildDebugLog).
    @Volatile private var lastNetworkPrep: NetworkPrepResult? = null
    @Volatile private var lastDnsSelfTest: DnsSelfTestReport? = null
    private val networkController: ProcessNetworkController? =
        processNetworkControllerFrom(appContext)

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
        val bundled = File(appContext.applicationInfo.nativeLibraryDir, binaryName)
        return bundled.takeIf { it.exists() && it.length() > 100_000 }
    }

    private fun setStatus(value: String, onStatus: (String) -> Unit) {
        status = value
        AppState.tunnelStatus = value
        // Notification / UI callbacks should land on main; AppState is already safe.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStatus(value)
        } else {
            mainHandler.post { onStatus(value) }
        }
    }

    /**
     * Publish a status string only while [generation] is still current, under the
     * run-state lock, so a cancelled background start cannot clobber the Stopped
     * status (or a newer generation's status) already published by stop()/start().
     */
    private fun publishStatus(
        value: String,
        generation: Long,
        onStatus: (String) -> Unit,
    ) {
        synchronized(runStateLock) {
            if (generation != runGeneration.get() || manualStopRequested) return
            setStatus(value, onStatus)
        }
    }

    /**
     * Publish the discovered Quick Tunnel URL + Connected status only while
     * [generation] is current, under the lock, so stop() clearing tunnelUrl cannot
     * be undone by a stale reader thread.
     */
    private fun publishTunnelUrl(
        url: String,
        generation: Long,
        onStatus: (String) -> Unit,
    ) {
        synchronized(runStateLock) {
            if (generation != runGeneration.get() || manualStopRequested) return
            tunnelUrl = url
            AppState.tunnelUrl = url
            setStatus("Connected", onStatus)
        }
    }

    /**
     * Handle a start that failed without a live adopted process: tear down any
     * child spawned, clear the process network binding this generation established
     * (only when the generation is still current), and surface the failure status.
     */
    private fun onFailedStart(
        started: Process?,
        generation: Long,
        onStatus: (String) -> Unit,
        message: String,
    ) {
        synchronized(runStateLock) {
            if (generation != runGeneration.get() || manualStopRequested) return
            if (started != null && process === started) {
                process = null
            }
            if (started != null) {
                try {
                    started.destroyForcibly()
                } catch (e: Exception) {
                    Log.w(TAG, "destroyForcibly after failed start", e)
                }
            }
            try {
                networkController?.clearProcessNetworkBinding()
            } catch (e: Exception) {
                Log.w(TAG, "clearProcessNetworkBinding after failed start", e)
            }
        }
        publishStatus(message, generation, onStatus)
    }

    /**
     * Bind + DNS preflight + ProcessBuilder.start. MUST run off the main thread —
     * InetAddress DNS throws NetworkOnMainThreadException on API 36+ strict mode paths
     * and a hanging resolver would ANR the service.
     */
    private fun prepareAndRun(args: List<String>, onStatus: (String) -> Unit, generation: Long) {
        if (generation != runGeneration.get() || manualStopRequested) {
            return
        }

        val bin = getBinaryFile()
        if (bin == null) {
            val reason = if (isSupportedAbi()) {
                "cloudflared binary missing from native libs"
            } else {
                "cloudflared bundled for arm64 only — unsupported device ABI (${Build.SUPPORTED_ABIS.joinToString()})"
            }
            Log.e(TAG, reason)
            publishStatus(reason, generation, onStatus)
            return
        }

        // Bind this process to the active network BEFORE exec'ing cloudflared.
        // The bind is correct + harmless for the parent (its sockets, including
        // the child's, route over the active network) but it does NOT cover the
        // child's DNS — that is the bundled cgo/NDK binary's job (bionic
        // getaddrinfo → netd). Preflight DNS via Android APIs surfaces a clear
        // "no working network/DNS" status instead of a cryptic cloudflared exit.
        val prep = TunnelNetworkPrep.prepare(networkController)
        lastNetworkPrep = prep
        prep.debugLines().forEach { Log.i(TAG, "network prep: $it") }

        // Stop/start raced during preflight — do not clobber Stopped with prep status.
        if (generation != runGeneration.get() || manualStopRequested) {
            try {
                networkController?.clearProcessNetworkBinding()
            } catch (e: Exception) {
                Log.w(TAG, "clearProcessNetworkBinding after cancelled start", e)
            }
            return
        }

        if (!prep.canStart) {
            val reason = prep.blockedStatus ?: "No working network/DNS"
            Log.e(TAG, "Refusing to start cloudflared: $reason")
            publishStatus(reason, generation, onStatus)
            return
        }

        runBinary(bin, args, onStatus, generation)
    }

    private fun runBinary(
        bin: File,
        args: List<String>,
        onStatus: (String) -> Unit,
        generation: Long,
    ) {
        var started: Process? = null
        val lastOutputLine = AtomicReference<String?>()
        try {
            // nativeLibraryDir is extracted with exec permission; no chmod needed.
            val fullCommand = listOf(bin.absolutePath) + args
            val pb = ProcessBuilder(fullCommand)
            pb.redirectErrorStream(true)

            // Go binaries need a writable temp dir; cloudflared also reads its config
            // from HOME. The app's own dirs are the only guaranteed-writable locations.
            val env = pb.environment()
            val homeDir = appContext.filesDir.absolutePath
            val tmpDir = appContext.cacheDir.absolutePath
            env["HOME"] = homeDir
            env["TMPDIR"] = tmpDir
            env["TMP"] = tmpDir
            env["TEMP"] = tmpDir

            synchronized(runStateLock) {
                if (generation != runGeneration.get() || manualStopRequested) {
                    try {
                        networkController?.clearProcessNetworkBinding()
                    } catch (e: Exception) {
                        Log.w(TAG, "clearProcessNetworkBinding after cancelled start", e)
                    }
                    return
                }
                lastExitCode = null
                lastRunStartedAtMs = System.currentTimeMillis()
                lastRunDurationMs = null
            }
            lastCommand = fullCommand
            lastEnvOverrides = mapOf(
                "HOME" to homeDir,
                "TMPDIR" to tmpDir,
                "TMP" to tmpDir,
                "TEMP" to tmpDir
            )
            lastBinary = bin
            val proc = pb.start()
            started = proc
            // stop()/a newer enqueueStart() may have bumped generation while ProcessBuilder
            // was starting; never publish a cancelled child into process.
            val adopted = synchronized(runStateLock) {
                if (generation != runGeneration.get() || manualStopRequested) {
                    try {
                        proc.destroyForcibly()
                    } catch (e: Exception) {
                        Log.w(TAG, "destroyForcibly after cancelled start", e)
                    }
                    // Do not clearProcessNetworkBinding here: stop()/a newer start owns the
                    // bind and may already have re-bound for the live generation.
                    false
                } else {
                    process = proc
                    true
                }
            }
            if (!adopted) {
                return
            }

            publishStatus("Starting tunnel...", generation, onStatus)

            val readerThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        lastOutputLine.set(text)
                        Log.d(TAG, "cloudflared: $text")
                        synchronized(logBufferLock) { TunnelLog.append(logBuffer, text) }
                        if (generation != runGeneration.get()) continue
                        if (text.contains("Registered tunnel connection")) {
                            publishStatus("Connected", generation, onStatus)
                        } else if (text.contains("https://") && tunnelUrl == null) {
                            val match = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(text)
                            if (match != null) {
                                publishTunnelUrl(match.value, generation, onStatus)
                            }
                        } else if (text.contains("error", ignoreCase = true)) {
                            // Only update status if we aren't connected
                            if (status != "Connected") {
                                publishStatus("cloudflared error: ${text.trim().take(200)}", generation, onStatus)
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
                synchronized(runStateLock) {
                    if (generation != runGeneration.get()) return@synchronized
                    // Only clear our own slot; a newer generation may already own process.
                    if (process === proc) {
                        process = null
                    }
                    lastExitCode = exit
                    val durationMs = System.currentTimeMillis() - (lastRunStartedAtMs ?: System.currentTimeMillis())
                    lastRunDurationMs = durationMs
                    val lastLine = lastOutputLine.get()
                    val finalStatus = if (manualStopRequested) {
                        "Stopped"
                    } else {
                        TunnelLog.exitStatus(exit, lastLine)
                    }
                    setStatus(finalStatus, onStatus)
                    Log.w(TAG, "cloudflared exited with code $exit after ${durationMs}ms")
                }
            }.start()

        } catch (e: IOException) {
            Log.e(TAG, "Failed to start cloudflared", e)
            val hint = if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                " — device refused to exec the bundled binary"
            } else {
                ""
            }
            onFailedStart(
                started,
                generation,
                onStatus,
                "Failed to start cloudflared: ${e.message ?: e.javaClass.simpleName}$hint",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cloudflared", e)
            onFailedStart(
                started,
                generation,
                onStatus,
                "Failed to start cloudflared: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Begin a new start generation on the caller thread (may be main), then hand
     * bind/DNS/exec to [startExecutor]. Safe to call from Service.onStartCommand.
     */
    private fun enqueueStart(args: List<String>, onStatus: (String) -> Unit) {
        // stop() bumps generation and clears any previous process/binding.
        stop()
        val generation = synchronized(runStateLock) {
            manualStopRequested = false
            runGeneration.incrementAndGet()
        }
        setStatus("Preparing network...", onStatus)
        startExecutor.execute {
            try {
                prepareAndRun(args, onStatus, generation)
            } catch (e: Exception) {
                Log.e(TAG, "Background tunnel start failed", e)
                val msg = if (isNetworkOnMainThreadException(e)) {
                    "BUG: network/DNS on main thread (NetworkOnMainThreadException)"
                } else {
                    "Failed to start cloudflared: ${e.message ?: e.javaClass.simpleName}"
                }
                synchronized(runStateLock) {
                    if (generation == runGeneration.get() && !manualStopRequested) {
                        try {
                            networkController?.clearProcessNetworkBinding()
                        } catch (e2: Exception) {
                            Log.w(TAG, "clearProcessNetworkBinding after background start failure", e2)
                        }
                    }
                }
                publishStatus(msg, generation, onStatus)
            }
        }
    }

    fun startNamedTunnel(token: String, onStatus: (String) -> Unit = {}) {
        enqueueStart(TunnelArgs.namedTunnel(token), onStatus)
    }

    fun startQuickTunnel(localPort: Int = 5338, onStatus: (String) -> Unit = {}) {
        enqueueStart(TunnelArgs.quickTunnel(localPort), onStatus)
    }

    /**
     * One-tap DNS/network self-test. Always runs on [startExecutor]; reports on
     * screen via [onResult] (main thread) and is included in [buildDebugLog].
     */
    fun runDnsSelfTest(onResult: (String) -> Unit = {}) {
        startExecutor.execute {
            val report = try {
                TunnelDnsSelfTest.run(
                    controller = networkController,
                    binaryFile = getBinaryFile()
                        ?: File(appContext.applicationInfo.nativeLibraryDir, binaryName)
                            .takeIf { it.exists() },
                )
            } catch (e: Exception) {
                Log.e(TAG, "DNS self-test crashed", e)
                DnsSelfTestReport(
                    passed = false,
                    lines = listOf("crash: ${e.javaClass.simpleName}: ${e.message ?: ""}"),
                    summary = "DNS self-test FAILED (crash)",
                )
            }
            lastDnsSelfTest = report
            AppState.dnsSelfTestResult = report.asText()
            // If a tunnel is not running, ensure we are not left bound from the test.
            if (AppState.cloudflaredManager === this && !isRunning()) {
                try {
                    networkController?.clearProcessNetworkBinding()
                } catch (e: Exception) {
                    Log.w(TAG, "clearProcessNetworkBinding after self-test", e)
                }
            }
            val text = report.asText()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onResult(text)
            } else {
                mainHandler.post { onResult(text) }
            }
        }
    }

    fun stop() {
        manualStopRequested = true
        val toDestroy: Process?
        synchronized(runStateLock) {
            runGeneration.incrementAndGet()
            toDestroy = process
            process = null
        }
        try {
            toDestroy?.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "destroyForcibly on stop failed", e)
        }
        tunnelUrl = null
        AppState.tunnelUrl = null
        status = "Stopped"
        AppState.tunnelStatus = status
        // Drop the process↔network binding established in prepareAndRun so we do
        // not keep the app pinned to a network that may go away after the tunnel
        // stops (e.g. Wi‑Fi → cellular handoff while idle).
        try {
            networkController?.clearProcessNetworkBinding()
        } catch (e: Exception) {
            Log.w(TAG, "clearProcessNetworkBinding failed", e)
        }
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
        // Which DNS resolver the child will use is decided by how the binary is
        // linked. Surfaces "static pure-Go resolver" vs "cgo/bionic → netd" so a
        // failing child run tells us which DNS path it took, not just that it failed.
        val bin = lastBinary
        if (bin != null) {
            val info = try {
                TunnelBinaryInspector.inspect(bin)
            } catch (e: Exception) {
                BinaryInfo(
                    isElf = false, is64Bit = false, machine = null,
                    isDynamic = false, interp = null,
                    error = "inspector threw ${e.javaClass.simpleName}: ${e.message ?: ""}",
                )
            }
            sb.appendLine("Cloudflared resolver path: ${info.summary()}")
        } else {
            sb.appendLine("Cloudflared resolver path: n/a (binary not found)")
        }
        sb.appendLine()
        sb.appendLine("--- Network bind / DNS preflight ---")
        val prep = lastNetworkPrep
        if (prep == null) {
            sb.appendLine("(no preflight recorded yet — start the tunnel first)")
        } else {
            prep.debugLines().forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("--- DNS / network self-test ---")
        val selfTest = lastDnsSelfTest?.asText() ?: AppState.dnsSelfTestResult
        if (selfTest.isNullOrBlank()) {
            sb.appendLine("(no self-test recorded yet — tap DNS Self-Test)")
        } else {
            sb.appendLine(selfTest)
        }
        sb.appendLine()
        sb.appendLine("--- cloudflared invocation ---")
        sb.appendLine("Command: ${TunnelLog.redactCommand(lastCommand)}")
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
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun appVersionCode(): Long = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toLong()
        }
    } catch (e: Exception) {
        -1L
    }

    companion object {
        private val defaultStartExecutor: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cloudflared-start").apply { isDaemon = true }
        }
    }
}
