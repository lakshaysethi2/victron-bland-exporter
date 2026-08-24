package com.lakshaysethi.victronbleexporter.charger

import org.junit.Assert.assertEquals
import org.junit.Test

class ExporterKeepAliveAlarmTest {
    @Test
    fun `next keep-alive is 15 minutes after now`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 15 * 60 * 1000L, ExporterKeepAliveAlarm.nextAt(now))
        assertEquals(15 * 60 * 1000L, ExporterKeepAliveAlarm.INTERVAL_MS)
    }
}
