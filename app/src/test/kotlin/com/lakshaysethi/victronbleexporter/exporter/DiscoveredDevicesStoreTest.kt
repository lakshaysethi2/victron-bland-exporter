package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscoveredDevicesStoreTest {

    @Before
    fun setUp() {
        DiscoveredDevicesStore.clear()
    }

    @Test
    fun `service decrypt failure marks wrong key`() {
        DiscoveredDevicesStore.updateSeen(
            mac = "aa:bb:cc:dd:ee:ff",
            modelId = 0xA042,
            recordType = 1,
            rssi = -60,
            hasKey = true,
            parsed = null,
            decryptFailed = true
        )

        val device = DiscoveredDevicesStore.get("AA:BB:CC:DD:EE:FF")!!
        assertTrue(device.wrongKey)
        assertTrue(device.needsKey)
        assertTrue(device.hasKey)
    }

    @Test
    fun `ui scanner without a parse attempt does not invent wrong key`() {
        DiscoveredDevicesStore.updateSeen(
            mac = "AA:BB:CC:DD:EE:FF",
            modelId = 0xA042,
            recordType = 1,
            rssi = -60,
            hasKey = true,
            parsed = null
        )

        assertFalse(DiscoveredDevicesStore.get("AA:BB:CC:DD:EE:FF")!!.wrongKey)
    }

    @Test
    fun `successful parse and new key both clear wrong key`() {
        DiscoveredDevicesStore.updateSeen(
            mac = "AA:BB:CC:DD:EE:FF",
            modelId = 0xA042,
            recordType = 1,
            rssi = -60,
            hasKey = true,
            parsed = null,
            decryptFailed = true
        )
        DiscoveredDevicesStore.updateSeen(
            mac = "AA:BB:CC:DD:EE:FF",
            modelId = 0xA042,
            recordType = 1,
            rssi = -55,
            hasKey = true,
            parsed = ParsedDevice("AA:BB:CC:DD:EE:FF", 0xA042, 1, emptyMap(), -55)
        )
        assertFalse(DiscoveredDevicesStore.get("AA:BB:CC:DD:EE:FF")!!.wrongKey)

        DiscoveredDevicesStore.updateSeen(
            mac = "AA:BB:CC:DD:EE:FF",
            modelId = 0xA042,
            recordType = 1,
            rssi = -55,
            hasKey = true,
            parsed = null,
            decryptFailed = true
        )
        DiscoveredDevicesStore.markHasKey("AA:BB:CC:DD:EE:FF", true)
        assertFalse(DiscoveredDevicesStore.get("AA:BB:CC:DD:EE:FF")!!.wrongKey)
    }
}
