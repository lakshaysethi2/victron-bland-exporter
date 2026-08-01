package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

private const val TAG = "TunnelNetworkPrep"

/** Host cloudflared hits first for a Quick Tunnel; used for Android-side DNS preflight. */
internal const val CLOUDFLARE_PREFLIGHT_HOST = "api.trycloudflare.com"

/** Secondary host used by the in-app DNS self-test. */
internal const val CLOUDFLARE_SELFTEST_HOST = "cloudflare.com"

/**
 * Snapshot of the bind + DNS preflight that must succeed before we exec cloudflared.
 * Native Go DNS resolves via localhost netd ([::1]:53); without bindProcessToNetwork
 * Android refuses those queries even when the app process itself can resolve hosts.
 */
internal data class NetworkPrepResult(
    val canStart: Boolean,
    /** Human-readable failure for the status line when [canStart] is false. */
    val blockedStatus: String? = null,
    val activeNetworkLabel: String? = null,
    val bindCalled: Boolean = false,
    val bindSucceeded: Boolean = false,
    val dnsHost: String = CLOUDFLARE_PREFLIGHT_HOST,
    val dnsIps: List<String> = emptyList(),
    val dnsError: String? = null,
) {
    fun debugLines(): List<String> = listOf(
        "activeNetwork: ${activeNetworkLabel ?: "null"}",
        "bindProcessToNetwork called: $bindCalled",
        "bindProcessToNetwork succeeded: $bindSucceeded",
        "preflight DNS host: $dnsHost",
        "preflight DNS IPs: ${if (dnsIps.isEmpty()) "(none)" else dnsIps.joinToString(", ")}",
        "preflight DNS error: ${dnsError ?: "(none)"}",
        "canStart cloudflared: $canStart",
        blockedStatus?.let { "blocked status: $it" } ?: "blocked status: (none)",
    )
}

/** Active-network snapshot used by prep and the in-app DNS self-test. */
internal data class NetworkDiagnostics(
    val activeNetworkLabel: String?,
    val hasInternet: Boolean? = null,
    val isValidated: Boolean? = null,
    val dnsServers: List<String> = emptyList(),
)

/**
 * Abstraction over [ConnectivityManager.bindProcessToNetwork] so the prep flow is
 * unit-testable with a fake (JVM unit tests cannot call real Android netd).
 */
internal interface ProcessNetworkController {
    /** Label for the active network, or null when there is none. */
    fun activeNetworkLabel(): String?

    /**
     * Bind this process to the active default network so native subprocess DNS
     * (cloudflared/Go → [::1]:53) is accepted by Android netd.
     * @return false when there is no active network to bind to
     */
    fun bindProcessToActiveNetwork(): Boolean

    /**
     * Clear the process↔network binding so the process follows the system default
     * again. Called on tunnel stop so we do not leave a stale binding if the
     * default network changes after cloudflared exits.
     */
    fun clearProcessNetworkBinding()

    /** Richer snapshot for debug / self-test (capabilities + system DNS servers). */
    fun networkDiagnostics(): NetworkDiagnostics
}

/** Production controller backed by [ConnectivityManager]. */
internal class ConnectivityManagerNetworkController(
    private val connectivityManager: ConnectivityManager,
) : ProcessNetworkController {

    override fun activeNetworkLabel(): String? {
        val network: Network = connectivityManager.activeNetwork ?: return null
        // Network.toString() is stable enough for debug logs (e.g. "100").
        return network.toString()
    }

    override fun bindProcessToActiveNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val ok = connectivityManager.bindProcessToNetwork(network)
        Log.i(TAG, "bindProcessToNetwork($network) → $ok")
        return ok
    }

    override fun clearProcessNetworkBinding() {
        // null clears the binding established before starting cloudflared.
        val ok = connectivityManager.bindProcessToNetwork(null)
        Log.i(TAG, "bindProcessToNetwork(null) clear → $ok")
    }

    override fun networkDiagnostics(): NetworkDiagnostics {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            return NetworkDiagnostics(activeNetworkLabel = null)
        }
        val caps = connectivityManager.getNetworkCapabilities(network)
        val link = connectivityManager.getLinkProperties(network)
        val dns = link?.dnsServers?.mapNotNull { it.hostAddress?.takeIf { ip -> ip.isNotBlank() } }
            ?: emptyList()
        return NetworkDiagnostics(
            activeNetworkLabel = network.toString(),
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            dnsServers = dns,
        )
    }
}

internal fun processNetworkControllerFrom(context: Context): ProcessNetworkController? {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: return null
    return ConnectivityManagerNetworkController(cm)
}

/** True when [e] (or a cause) is Android's NetworkOnMainThreadException. */
internal fun isNetworkOnMainThreadException(e: Throwable): Boolean {
    var cur: Throwable? = e
    while (cur != null) {
        val name = cur.javaClass.name
        if (name == "android.os.NetworkOnMainThreadException" ||
            cur.javaClass.simpleName == "NetworkOnMainThreadException"
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

/**
 * Pure-ish prep helper: bind the process to the active network, then resolve
 * [dnsHost] via the supplied resolver (defaults to [InetAddress.getAllByName]).
 *
 * Order matters — bind first so the preflight query (and later cloudflared) use
 * the active network's DNS path rather than an unbound localhost stub.
 *
 * On DNS preflight failure after a successful bind, the process network binding
 * is cleared so the app is not left pinned to a network after a refused start.
 */
internal object TunnelNetworkPrep {

    fun prepare(
        controller: ProcessNetworkController?,
        dnsHost: String = CLOUDFLARE_PREFLIGHT_HOST,
        resolve: (String) -> List<String> = ::defaultResolve,
    ): NetworkPrepResult {
        if (controller == null) {
            return NetworkPrepResult(
                canStart = false,
                blockedStatus = "No working network/DNS — ConnectivityManager unavailable",
                dnsHost = dnsHost,
                dnsError = "ConnectivityManager unavailable",
            )
        }

        val activeLabel = controller.activeNetworkLabel()
        if (activeLabel == null) {
            return NetworkPrepResult(
                canStart = false,
                blockedStatus = "No working network/DNS — no active network",
                activeNetworkLabel = null,
                bindCalled = false,
                bindSucceeded = false,
                dnsHost = dnsHost,
                dnsError = "no active network",
            )
        }

        val bindOk = controller.bindProcessToActiveNetwork()
        if (!bindOk) {
            // activeNetwork was non-null above; bind can still fail if the network
            // disappeared between the two calls, or if the platform rejects the bind.
            return NetworkPrepResult(
                canStart = false,
                blockedStatus = "No working network/DNS — bindProcessToNetwork failed",
                activeNetworkLabel = activeLabel,
                bindCalled = true,
                bindSucceeded = false,
                dnsHost = dnsHost,
                dnsError = "bindProcessToNetwork returned false",
            )
        }

        return try {
            val ips = resolve(dnsHost)
            if (ips.isEmpty()) {
                clearBindingQuietly(controller)
                NetworkPrepResult(
                    canStart = false,
                    blockedStatus = "No working network/DNS — $dnsHost resolved to no addresses",
                    activeNetworkLabel = activeLabel,
                    bindCalled = true,
                    bindSucceeded = true,
                    dnsHost = dnsHost,
                    dnsIps = emptyList(),
                    dnsError = "empty DNS result",
                )
            } else {
                NetworkPrepResult(
                    canStart = true,
                    activeNetworkLabel = activeLabel,
                    bindCalled = true,
                    bindSucceeded = true,
                    dnsHost = dnsHost,
                    dnsIps = ips,
                )
            }
        } catch (e: Exception) {
            clearBindingQuietly(controller)
            if (isNetworkOnMainThreadException(e)) {
                // Wrong-thread bug, not missing connectivity. Distinct status so it is
                // never mistaken for "no network".
                NetworkPrepResult(
                    canStart = false,
                    blockedStatus = "BUG: network/DNS on main thread (NetworkOnMainThreadException)",
                    activeNetworkLabel = activeLabel,
                    bindCalled = true,
                    bindSucceeded = true,
                    dnsHost = dnsHost,
                    dnsError = "NetworkOnMainThreadException (wrong thread, not a connectivity failure)",
                )
            } else {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                NetworkPrepResult(
                    canStart = false,
                    blockedStatus = "No working network/DNS — cannot resolve $dnsHost: $detail",
                    activeNetworkLabel = activeLabel,
                    bindCalled = true,
                    bindSucceeded = true,
                    dnsHost = dnsHost,
                    dnsError = detail,
                )
            }
        }
    }

    private fun clearBindingQuietly(controller: ProcessNetworkController) {
        try {
            controller.clearProcessNetworkBinding()
        } catch (e: Exception) {
            Log.w(TAG, "clearProcessNetworkBinding after failed preflight", e)
        }
    }

    internal fun defaultResolve(host: String): List<String> {
        // Android API path (not cloudflared). getAllByName throws UnknownHostException on failure.
        return InetAddress.getAllByName(host).mapNotNull { addr ->
            addr.hostAddress?.takeIf { it.isNotBlank() }
        }
    }
}

/** Result of the one-tap in-app DNS / network self-test. */
internal data class DnsSelfTestReport(
    val passed: Boolean,
    val lines: List<String>,
    val summary: String,
) {
    fun asText(): String = (listOf(summary) + lines).joinToString("\n")
}

/**
 * In-app DNS/network self-test. Always intended to run on a background executor —
 * fails hard if invoked on the main looper.
 */
internal object TunnelDnsSelfTest {

    fun run(
        controller: ProcessNetworkController?,
        binaryFile: File?,
        isMainThread: () -> Boolean = {
            Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()
        },
        threadName: () -> String = { Thread.currentThread().name },
        resolve: (String) -> List<String> = TunnelNetworkPrep::defaultResolve,
        httpProbe: (String) -> String = ::defaultHttpProbe,
        nowMs: () -> Long = System::currentTimeMillis,
    ): DnsSelfTestReport {
        val lines = mutableListOf<String>()
        var passed = true

        fun fail(msg: String) {
            passed = false
            lines.add("FAIL: $msg")
        }

        fun ok(msg: String) {
            lines.add("OK: $msg")
        }

        lines.add("thread: ${threadName()}")
        if (isMainThread()) {
            fail("running on main looper thread — self-test must use a background executor")
            return DnsSelfTestReport(
                passed = false,
                lines = lines,
                summary = "DNS self-test FAILED (main thread)",
            )
        } else {
            ok("not on main looper")
        }

        val diag = controller?.networkDiagnostics()
        if (controller == null || diag == null) {
            fail("ConnectivityManager / network controller unavailable")
        } else {
            lines.add("activeNetwork: ${diag.activeNetworkLabel ?: "null"}")
            lines.add("NET_CAPABILITY_INTERNET: ${diag.hasInternet ?: "unknown"}")
            lines.add("NET_CAPABILITY_VALIDATED: ${diag.isValidated ?: "unknown"}")
            lines.add(
                "system DNS servers: ${
                    if (diag.dnsServers.isEmpty()) "(none)" else diag.dnsServers.joinToString(", ")
                }",
            )
            if (diag.activeNetworkLabel == null) {
                fail("no activeNetwork")
            } else {
                ok("activeNetwork present")
            }
        }

        var bindOk = false
        if (controller != null) {
            try {
                bindOk = controller.bindProcessToActiveNetwork()
                lines.add("bindProcessToNetwork: ${if (bindOk) "SUCCEEDED" else "FAILED"}")
                if (!bindOk) fail("bindProcessToNetwork returned false")
                else ok("bindProcessToNetwork")
            } catch (e: Exception) {
                if (isNetworkOnMainThreadException(e)) {
                    fail("bind threw NetworkOnMainThreadException (wrong thread bug)")
                } else {
                    fail("bind threw ${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
            }
        }

        for (host in listOf(CLOUDFLARE_PREFLIGHT_HOST, CLOUDFLARE_SELFTEST_HOST)) {
            val started = nowMs()
            try {
                val ips = resolve(host)
                val elapsed = nowMs() - started
                if (ips.isEmpty()) {
                    fail("resolve $host → empty (${elapsed}ms) thread=${threadName()}")
                } else {
                    ok("resolve $host → ${ips.joinToString(", ")} (${elapsed}ms) thread=${threadName()}")
                }
            } catch (e: Exception) {
                val elapsed = nowMs() - started
                if (isNetworkOnMainThreadException(e)) {
                    fail(
                        "resolve $host NetworkOnMainThreadException (${elapsed}ms) " +
                            "thread=${threadName()} — wrong thread bug, not missing connectivity",
                    )
                } else {
                    fail(
                        "resolve $host ${e.javaClass.simpleName}: ${e.message ?: ""} " +
                            "(${elapsed}ms) thread=${threadName()}",
                    )
                }
            }
        }

        if (binaryFile == null) {
            fail("libcloudflared.so not found / too small")
            lines.add("libcloudflared.so: missing")
        } else {
            val size = binaryFile.length()
            lines.add("libcloudflared.so: ${binaryFile.absolutePath} ($size bytes)")
            if (size <= 100_000L) {
                fail("libcloudflared.so present but suspiciously small ($size bytes)")
            } else {
                ok("libcloudflared.so present ($size bytes)")
            }
        }

        // Optional HTTPS probes — informational; DNS can still pass without them.
        for (url in listOf("https://api.trycloudflare.com", "https://1.1.1.1")) {
            val started = nowMs()
            try {
                val detail = httpProbe(url)
                val elapsed = nowMs() - started
                lines.add("HTTPS $url → $detail (${elapsed}ms) thread=${threadName()}")
            } catch (e: Exception) {
                val elapsed = nowMs() - started
                lines.add(
                    "HTTPS $url → ${e.javaClass.simpleName}: ${e.message ?: ""} " +
                        "(${elapsed}ms) thread=${threadName()}",
                )
            }
        }

        if (controller != null && bindOk) {
            // Never clear here: a live tunnel may own the process network bind.
            // CloudflaredManager clears only when !isRunning() after the self-test.
            lines.add("bindProcessToNetwork(null) clear: deferred to caller")
        }

        val summary = if (passed) "DNS self-test PASSED" else "DNS self-test FAILED"
        lines.add(0, "result: $summary")
        return DnsSelfTestReport(passed = passed, lines = lines, summary = summary)
    }

    internal fun defaultHttpProbe(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                requestMethod = "HEAD"
                useCaches = false
            }
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                // Some edges dislike HEAD; fall back to GET.
                conn.disconnect()
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                    readTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                    requestMethod = "GET"
                    useCaches = false
                }
                conn.responseCode
            }
            "HTTP $code"
        } finally {
            conn?.disconnect()
        }
    }
}
