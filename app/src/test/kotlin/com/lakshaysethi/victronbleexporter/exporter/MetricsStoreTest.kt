package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsStoreTest {

    private fun device(mac: String) = ParsedDevice(
        mac = mac,
        modelId = 0xA042,
        recordType = 1,
        data = mapOf("solar_power_w" to 100),
        rssi = -60,
    )

    @Test
    fun `update records the last-seen timestamp and replaces the entry`() {
        MetricsStore.clear()
        MetricsStore.update(device("AA:BB:CC:DD:EE:FF"), seenAt = 1_000L)
        MetricsStore.update(device("AA:BB:CC:DD:EE:FF"), seenAt = 2_000L)

        assertEquals(2_000L, MetricsStore.lastSeenMillis("AA:BB:CC:DD:EE:FF"))
        assertEquals(1, MetricsStore.count())
        // Unknown MAC -> 0 (never seen)
        assertEquals(0L, MetricsStore.lastSeenMillis("00:00:00:00:00:00"))
        assertNull(MetricsStore.get("00:00:00:00:00:00"))
    }

    @Test
    fun `clear resets timestamps too`() {
        MetricsStore.clear()
        MetricsStore.update(device("AA:BB:CC:DD:EE:FF"), seenAt = 1_000L)
        MetricsStore.clear()
        assertEquals(0, MetricsStore.count())
        assertEquals(0L, MetricsStore.lastSeenMillis("AA:BB:CC:DD:EE:FF"))
    }
}
