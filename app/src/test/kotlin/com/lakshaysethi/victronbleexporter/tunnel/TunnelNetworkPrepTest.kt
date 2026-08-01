package com.lakshaysethi.victronbleexporter.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake [ProcessNetworkController] standing in for a mocked ConnectivityManager:
 * records bind/clear calls and lets tests drive active-network presence.
 */
private class FakeProcessNetworkController(
    private var activeLabel: String? = "100",
    private var bindResult: Boolean = true,
) : ProcessNetworkController {
    var bindCalls = 0
        private set
    var clearCalls = 0
        private set

    fun setActive(label: String?) {
        activeLabel = label
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
}

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
    fun `preflight DNS failure short-circuits start after bind`() {
        val controller = FakeProcessNetworkController(activeLabel = "100")

        val result = TunnelNetworkPrep.prepare(controller) {
            throw IOException("connection refused")
        }

        assertFalse(result.canStart)
        val blocked = result.blockedStatus
        assertTrue(blocked != null && blocked.startsWith("No working network/DNS — cannot resolve"))
        assertTrue(blocked != null && blocked.contains("connection refused"))
        assertEquals(1, controller.bindCalls)
        assertTrue(result.bindCalled)
        assertTrue(result.bindSucceeded)
        assertEquals(emptyList<String>(), result.dnsIps)
        assertEquals("connection refused", result.dnsError)
    }

    @Test
    fun `empty DNS result blocks start`() {
        val controller = FakeProcessNetworkController(activeLabel = "100")

        val result = TunnelNetworkPrep.prepare(controller) { emptyList() }

        assertFalse(result.canStart)
        assertEquals(
            "No working network/DNS — $CLOUDFLARE_PREFLIGHT_HOST resolved to no addresses",
            result.blockedStatus,
        )
        assertEquals(1, controller.bindCalls)
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
}
