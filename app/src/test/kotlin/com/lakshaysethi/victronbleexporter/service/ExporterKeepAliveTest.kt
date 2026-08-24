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
}
