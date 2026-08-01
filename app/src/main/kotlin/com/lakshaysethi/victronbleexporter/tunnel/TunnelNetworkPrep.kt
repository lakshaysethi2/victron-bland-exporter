package com.lakshaysethi.victronbleexporter.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import java.net.InetAddress

private const val TAG = "TunnelNetworkPrep"

/** Host cloudflared hits first for a Quick Tunnel; used for Android-side DNS preflight. */
internal const val CLOUDFLARE_PREFLIGHT_HOST = "api.trycloudflare.com"

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
}

internal fun processNetworkControllerFrom(context: Context): ProcessNetworkController? {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: return null
    return ConnectivityManagerNetworkController(cm)
}

/**
 * Pure-ish prep helper: bind the process to the active network, then resolve
 * [dnsHost] via the supplied resolver (defaults to [InetAddress.getAllByName]).
 *
 * Order matters — bind first so the preflight query (and later cloudflared) use
 * the active network's DNS path rather than an unbound localhost stub.
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

    private fun defaultResolve(host: String): List<String> {
        // Android API path (not cloudflared). getAllByName throws UnknownHostException on failure.
        return InetAddress.getAllByName(host).mapNotNull { addr ->
            addr.hostAddress?.takeIf { it.isNotBlank() }
        }
    }
}
