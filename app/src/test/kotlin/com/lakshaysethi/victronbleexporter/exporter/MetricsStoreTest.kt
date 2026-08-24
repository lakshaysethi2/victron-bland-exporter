package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MetricsStoreTest {

    @Before
    fun setUp() {
        MetricsStore.clear()
    }

    @Test
    fun `fresh window is 90 seconds`() {
        assertFalse(MetricsStore.isFresh(10_000, 0))
        assertTrue(MetricsStore.isFresh(10_000, 1))
        assertTrue(MetricsStore.isFresh(90_000, 1))
        assertFalse(MetricsStore.isFresh(90_001, 1))
    }

    @Test
    fun `stale Instant Readout is dropped from live set and count`() {
        MetricsStore.update(
            ParsedDevice(
                mac = "AA:BB:CC:DD:EE:FF",
                modelId = 0xA042,
                recordType = 1,
                data = mapOf("solar_power_w" to 400),
                rssi = -55,
                lastSeen = 1_000L,
            )
        )

        assertEquals(1, MetricsStore.getAll().size)
        assertEquals(1, MetricsStore.getFresh(1_000L + 89_999L).size)
        assertEquals(1, MetricsStore.count(1_000L + 89_999L))
        assertTrue(MetricsStore.getFresh(1_000L + 90_000L).isEmpty())
        assertEquals(0, MetricsStore.count(1_000L + 90_000L))
        assertEquals(400, MetricsStore.getAll().values.single().data["solar_power_w"])
    }
}
