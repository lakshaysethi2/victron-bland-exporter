package com.lakshaysethi.victronbleexporter.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake [ProcessNetworkController] standing in for a mocked ConnectivityManager:
 * records bind/clear calls and lets tests drive active-network presence.
 */
private class FakeProcessNetworkController(
    private var activeLabel: String? = "100",
    private var bindResult: Boolean = true,
    private var diagnostics: NetworkDiagnostics = NetworkDiagnostics(
        activeNetworkLabel = "100",
        hasInternet = true,
        isValidated = true,
        dnsServers = listOf("1.1.1.1"),
    ),
) : ProcessNetworkController {
    var bindCalls = 0
        private set
    var clearCalls = 0
        private set

    fun setActive(label: String?) {
        activeLabel = label
        diagnostics = diagnostics.copy(activeNetworkLabel = label)
    }

    override fun activeNetworkLabel(): String? = activeLabel

    override fun bindProcessToActiveNetwork(): Boolean {
        bindCalls++
        if (activeLabel == null) return false
        return bindResult
    }

    override fun clearProcessNetworkBinding() {
        clearCalls++
    }

    override fun networkDiagnostics(): NetworkDiagnostics = diagnostics.copy(
        activeNetworkLabel = activeLabel,
    )
}

/** Stand-in exception whose simple name matches Android's NetworkOnMainThreadException. */
private class NetworkOnMainThreadException(message: String = "network on main") : RuntimeException(message)

class TunnelNetworkPrepTest {

    @Test
    fun `bind called with active network and DNS success allows start`() {
        val controller = FakeProcessNetworkController(activeLabel = "wifi-42")
        val resolveCalls = AtomicInteger(0)

        val result = TunnelNetworkPrep.prepare(controller) { host ->
            resolveCalls.incrementAndGet()
            assertEquals(CLOUDFLARE_PREFLIGHT_HOST, host)
            listOf("1.2.3.4", "2606:4700::1111")
        }

        assertTrue(result.canStart)
        assertNull(result.blockedStatus)
        assertEquals(1, controller.bindCalls)
        assertEquals(0, controller.clearCalls)
        assertTrue(result.bindCalled)
        assertTrue(result.bindSucceeded)
        assertEquals("wifi-42", result.activeNetworkLabel)
        assertEquals(listOf("1.2.3.4", "2606:4700::1111"), result.dnsIps)
        assertNull(result.dnsError)
        assertEquals(1, resolveCalls.get())
    }

    @Test
    fun `null active network returns early error without bind or DNS`() {
        val controller = FakeProcessNetworkController(activeLabel = null)
        val resolveCalls = AtomicInteger(0)

        val result = TunnelNetworkPrep.prepare(controller) {
            resolveCalls.incrementAndGet()
            listOf("1.2.3.4")
        }

        assertFalse(result.canStart)
        assertEquals("No working network/DNS — no active network", result.blockedStatus)
        assertEquals(0, controller.bindCalls)
        assertFalse(result.bindCalled)
        assertFalse(result.bindSucceeded)
        assertNull(result.activeNetworkLabel)
        assertEquals("no active network", result.dnsError)
        assertEquals(0, resolveCalls.get())
    }

    @Test
    fun `preflight DNS failure short-circuits start after bind and clears binding`() {
        val controller = FakeProcessNetworkController(activeLabel = "100")

        val result = TunnelNetworkPrep.prepare(controller) {
            throw IOException("connection refused")
        }

        assertFalse(result.canStart)
        val blocked = result.blockedStatus
        assertTrue(blocked != null && blocked.startsWith("No working network/DNS — cannot resolve"))
        assertTrue(blocked != null && blocked.contains("connection refused"))
        assertEquals(1, controller.bindCalls)
        assertEquals(1, controller.clearCalls)
        assertTrue(result.bindCalled)
        assertTrue(result.bindSucceeded)
        assertEquals(emptyList<String>(), result.dnsIps)
        assertEquals("connection refused", result.dnsError)
    }

    @Test
    fun `NetworkOnMainThreadException is distinct bug status not no-network`() {
        val controller = FakeProcessNetworkController(activeLabel = "850")

        val result = TunnelNetworkPrep.prepare(controller) {
            throw NetworkOnMainThreadException()
        }

        assertFalse(result.canStart)
        assertEquals(
            "BUG: network/DNS on main thread (NetworkOnMainThreadException)",
            result.blockedStatus,
        )
        assertTrue(result.dnsError!!.contains("wrong thread"))
        assertFalse(result.blockedStatus!!.contains("No working network/DNS"))
        assertEquals(1, controller.bindCalls)
        assertEquals(1, controller.clearCalls)
    }

    @Test
    fun `empty DNS result blocks start and clears binding`() {
        val controller = FakeProcessNetworkController(activeLabel = "100")

        val result = TunnelNetworkPrep.prepare(controller) { emptyList() }

        assertFalse(result.canStart)
        assertEquals(
            "No working network/DNS — $CLOUDFLARE_PREFLIGHT_HOST resolved to no addresses",
            result.blockedStatus,
        )
        assertEquals(1, controller.bindCalls)
        assertEquals(1, controller.clearCalls)
        assertEquals("empty DNS result", result.dnsError)
    }

    @Test
    fun `bindProcessToNetwork failure blocks start without DNS`() {
        val controller = FakeProcessNetworkController(activeLabel = "100", bindResult = false)
        val resolveCalls = AtomicInteger(0)

        val result = TunnelNetworkPrep.prepare(controller) {
            resolveCalls.incrementAndGet()
            listOf("1.2.3.4")
        }

        assertFalse(result.canStart)
        assertEquals("No working network/DNS — bindProcessToNetwork failed", result.blockedStatus)
        assertEquals(1, controller.bindCalls)
        assertEquals(0, controller.clearCalls)
        assertTrue(result.bindCalled)
        assertFalse(result.bindSucceeded)
        assertEquals(0, resolveCalls.get())
    }

    @Test
    fun `null controller is a hard failure`() {
        val result = TunnelNetworkPrep.prepare(null) { listOf("1.2.3.4") }

        assertFalse(result.canStart)
        assertEquals(
            "No working network/DNS — ConnectivityManager unavailable",
            result.blockedStatus,
        )
        assertFalse(result.bindCalled)
    }

    @Test
    fun `debug lines include bind and DNS fields`() {
        val controller = FakeProcessNetworkController(activeLabel = "net-7")
        val result = TunnelNetworkPrep.prepare(controller) { listOf("9.9.9.9") }
        val lines = result.debugLines()

        assertTrue(lines.any { it.contains("activeNetwork: net-7") })
        assertTrue(lines.any { it.contains("bindProcessToNetwork called: true") })
        assertTrue(lines.any { it.contains("preflight DNS IPs: 9.9.9.9") })
        assertTrue(lines.any { it.contains("canStart cloudflared: true") })
    }

    @Test
    fun `fake controller clearBinding is observable for stop path`() {
        val controller = FakeProcessNetworkController()
        assertEquals(0, controller.clearCalls)
        controller.clearProcessNetworkBinding()
        assertEquals(1, controller.clearCalls)
    }

    @Test
    fun `isNetworkOnMainThreadException matches simple name and cause chain`() {
        assertTrue(isNetworkOnMainThreadException(NetworkOnMainThreadException()))
        assertTrue(isNetworkOnMainThreadException(RuntimeException(NetworkOnMainThreadException())))
        assertFalse(isNetworkOnMainThreadException(IOException("connection refused")))
    }

    @Test
    fun `dns self-test fails hard when run on main thread`() {
        val controller = FakeProcessNetworkController()
        val report = TunnelDnsSelfTest.run(
            controller = controller,
            binaryFile = null,
            isMainThread = { true },
            threadName = { "main" },
            resolve = { listOf("1.2.3.4") },
            httpProbe = { "HTTP 200" },
        )
        assertFalse(report.passed)
        assertTrue(report.summary.contains("FAILED"))
        assertTrue(report.lines.any { it.contains("main looper") })
        assertEquals(0, controller.bindCalls)
    }

    @Test
    fun `dns self-test reports bind resolve binary and https on background thread`() {
        val controller = FakeProcessNetworkController(activeLabel = "850")
        val tmp = File.createTempFile("libcloudflared", ".so").apply {
            writeBytes(ByteArray(150_000) { 1 })
            deleteOnExit()
        }
        val hosts = mutableListOf<String>()
        val report = TunnelDnsSelfTest.run(
            controller = controller,
            binaryFile = tmp,
            isMainThread = { false },
            threadName = { "cloudflared-start" },
            resolve = { host ->
                hosts.add(host)
                listOf("1.1.1.1")
            },
            httpProbe = { url -> "HTTP 200 from $url" },
            nowMs = { 1_000L },
        )
        assertTrue(report.passed)
        assertEquals(listOf(CLOUDFLARE_PREFLIGHT_HOST, CLOUDFLARE_SELFTEST_HOST), hosts)
        assertEquals(1, controller.bindCalls)
        // Self-test must not clear; a live tunnel may own the process bind.
        assertEquals(0, controller.clearCalls)
        assertTrue(report.lines.any { it.contains("clear: deferred to caller") })
        assertTrue(report.lines.any { it.contains("activeNetwork: 850") })
        assertTrue(report.lines.any { it.contains("NET_CAPABILITY_INTERNET: true") })
        assertTrue(report.lines.any { it.contains("system DNS servers: 1.1.1.1") })
        assertTrue(report.lines.any { it.contains("libcloudflared.so") && it.contains("150000") })
        assertTrue(report.lines.any { it.contains("HTTPS https://api.trycloudflare.com") })
        assertTrue(report.lines.any { it.contains("thread: cloudflared-start") })
    }

    @Test
    fun `dns self-test treats NetworkOnMainThreadException on resolve as bug`() {
        val controller = FakeProcessNetworkController()
        val report = TunnelDnsSelfTest.run(
            controller = controller,
            binaryFile = File.createTempFile("cfbin", ".so").apply {
                writeBytes(ByteArray(150_000))
                deleteOnExit()
            },
            isMainThread = { false },
            threadName = { "bg" },
            resolve = { throw NetworkOnMainThreadException() },
            httpProbe = { "HTTP 200" },
        )
        assertFalse(report.passed)
        assertTrue(report.lines.any { it.contains("NetworkOnMainThreadException") && it.contains("wrong thread") })
    }
}
