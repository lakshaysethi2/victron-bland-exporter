package com.lakshaysethi.victronbleexporter.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterKeepAliveTest {

    @Test
    fun `restores named tunnel only when down and a token is saved`() {
        assertTrue(ExporterKeepAlive.shouldRestoreNamedTunnel(false, "eyJhbGciOi"))
        assertFalse(ExporterKeepAlive.shouldRestoreNamedTunnel(true, "eyJhbGciOi"))
        assertFalse(ExporterKeepAlive.shouldRestoreNamedTunnel(false, null))
        assertFalse(ExporterKeepAlive.shouldRestoreNamedTunnel(false, "   "))
        assertFalse(ExporterKeepAlive.shouldRestoreNamedTunnel(true, null))
    }

    @Test
    fun `does not restore a user-stopped tunnel but does restart a crash after 60s`() {
        assertEquals(60_000L, ExporterKeepAlive.TUNNEL_RESTART_AFTER_MS)
        assertFalse(ExporterKeepAlive.shouldRestoreNamedTunnel(false, "eyJhbGciOi", userStopped = true))
        assertFalse(ExporterKeepAlive.shouldRestoreNamedTunnel(false, "eyJhbGciOi", lastRestartAt = 1, now = 1 + 59_999))
        assertTrue(ExporterKeepAlive.shouldRestoreNamedTunnel(false, "eyJhbGciOi", lastRestartAt = 1, now = 1 + 60_000))
        assertTrue(ExporterKeepAlive.shouldRestoreNamedTunnel(false, "eyJhbGciOi", lastRestartAt = 0, now = 1))
    }

    @Test
    fun `schedule retry is one minute not ten`() {
        assertEquals(60_000L, ExporterKeepAlive.SCHEDULE_RETRY_MS)
        assertEquals(600_000L, ExporterKeepAlive.SCHEDULE_REENFORCE_MS)
    }

    @Test
    fun `schedule target prefers the stored mac then the first live device`() {
        assertEquals("AA:BB:CC:DD:EE:FF", ExporterKeepAlive.scheduleTargetMac("AA:BB:CC:DD:EE:FF", listOf("11:22:33:44:55:66")))
        assertEquals("11:22:33:44:55:66", ExporterKeepAlive.scheduleTargetMac("", listOf("11:22:33:44:55:66")))
        assertEquals("11:22:33:44:55:66", ExporterKeepAlive.scheduleTargetMac("   ", listOf("", "11:22:33:44:55:66")))
        assertEquals(null, ExporterKeepAlive.scheduleTargetMac("", emptyList()))
        assertEquals(null, ExporterKeepAlive.scheduleTargetMac(null, listOf("")))
    }

    @Test
    fun `schedule reapplies on window change or after 10 minutes`() {
        assertTrue(ExporterKeepAlive.shouldApplySchedule(true, null, 0, 1))
        assertTrue(ExporterKeepAlive.shouldApplySchedule(true, false, 1, 2))
        assertFalse(ExporterKeepAlive.shouldApplySchedule(true, true, 1, 1 + 599_999))
        assertTrue(ExporterKeepAlive.shouldApplySchedule(true, true, 1, 1 + 600_000))
        assertTrue(ExporterKeepAlive.shouldApplySchedule(false, false, 0, 1))
    }

    @Test
    fun `voltage poll is due after 60s and backs off 5min after an error`() {
        assertTrue(ExporterKeepAlive.voltagePollDue(60_000, 0, 0, null))
        assertFalse(ExporterKeepAlive.voltagePollDue(59_999, 0, 0, null))
        assertTrue(ExporterKeepAlive.voltagePollDue(120_000, 60_000, 60_000, null))
        assertFalse(ExporterKeepAlive.voltagePollDue(119_999, 60_000, 60_000, null))
        assertFalse(ExporterKeepAlive.voltagePollDue(60_000 + 299_999, 60_000, 0, "connect timeout"))
        assertTrue(ExporterKeepAlive.voltagePollDue(60_000 + 300_000, 60_000, 0, "connect timeout"))
    }

    @Test
    fun `panel voltage is omitted after 5 minutes without a fresh read`() {
        assertFalse(ExporterKeepAlive.voltageFresh(10_000, 0))
        assertTrue(ExporterKeepAlive.voltageFresh(10_000, 1))
        assertTrue(ExporterKeepAlive.voltageFresh(300_000, 1))
        assertFalse(ExporterKeepAlive.voltageFresh(300_001, 1))
    }

    @Test
    fun `scan restarts after 180s of silence but not before first start`() {
        assertEquals(180_000L, ExporterKeepAlive.SCAN_RESTART_AFTER_MS)
        assertFalse(ExporterKeepAlive.shouldRestartScan(0, 1_000_000))
        assertFalse(ExporterKeepAlive.shouldRestartScan(1, 180_000))
        assertTrue(ExporterKeepAlive.shouldRestartScan(1, 1 + 180_000))
    }
}
