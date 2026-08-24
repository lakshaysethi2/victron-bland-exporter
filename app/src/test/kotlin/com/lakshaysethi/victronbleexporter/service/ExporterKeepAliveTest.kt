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
    fun `schedule retry is one minute not ten`() {
        assertEquals(60_000L, ExporterKeepAlive.SCHEDULE_RETRY_MS)
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
