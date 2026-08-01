package com.lakshaysethi.victronbleexporter.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.ArrayDeque

class TunnelLogTest {

    @Test
    fun `ring buffer keeps only the last 200 lines`() {
        val buffer = ArrayDeque<String>()
        for (i in 1..250) {
            TunnelLog.append(buffer, "line $i")
        }
        assertEquals(TUNNEL_LOG_MAX_LINES, buffer.size)
        val snapshot = TunnelLog.snapshot(buffer)
        assertEquals(TUNNEL_LOG_MAX_LINES, snapshot.size)
        assertEquals("line 51", snapshot.first())
        assertEquals("line 250", snapshot.last())
        // Order preserved, oldest dropped.
        assertNull(snapshot.find { it == "line 50" })
    }

    @Test
    fun `ring buffer keeps everything when under the limit`() {
        val buffer = ArrayDeque<String>()
        TunnelLog.append(buffer, "a")
        TunnelLog.append(buffer, "b")
        TunnelLog.append(buffer, "c")
        assertEquals(listOf("a", "b", "c"), TunnelLog.snapshot(buffer))
        assertEquals("c", TunnelLog.lastLine(buffer))
    }

    @Test
    fun `empty buffer reports no last line`() {
        assertNull(TunnelLog.lastLine(ArrayDeque()))
        assertEquals(emptyList<String>(), TunnelLog.snapshot(ArrayDeque()))
    }

    @Test
    fun `exit status surfaces exit code and last output line`() {
        assertEquals(
            "cloudflared exited (code 1): ERR unable to register tunnel",
            TunnelLog.exitStatus(1, "ERR unable to register tunnel")
        )
    }

    @Test
    fun `exit status truncates very long last lines`() {
        val longLine = "x".repeat(500)
        val status = TunnelLog.exitStatus(1, longLine)
        assertEquals(200, status.substringAfter(": ").length)
    }

    @Test
    fun `exit status reports no output when nothing captured`() {
        assertEquals("cloudflared exited (code 1): (no output captured)", TunnelLog.exitStatus(1, null))
        assertEquals("cloudflared exited (code 1): (no output captured)", TunnelLog.exitStatus(1, "  "))
    }

    @Test
    fun `exit code zero is reported as stopped`() {
        assertEquals("Stopped", TunnelLog.exitStatus(0, "some line"))
    }

    @Test
    fun `quick tunnel args include no-autoupdate and real localhost port`() {
        val args = TunnelArgs.quickTunnel(5338)
        assertEquals(listOf("--no-autoupdate", "tunnel", "--url", "http://localhost:5338"), args)
    }

    @Test
    fun `named tunnel args include no-autoupdate and token`() {
        val args = TunnelArgs.namedTunnel("abc123")
        assertEquals(listOf("--no-autoupdate", "tunnel", "run", "--token", "abc123"), args)
    }
}
