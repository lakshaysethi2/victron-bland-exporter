package com.lakshaysethi.victronbleexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests using real captured advertisements from keshavdv/victron-ble test fixtures.
 * These are genuine on-air captures with known keys and expected values.
 */
class VictronParserTest {

    private fun String.hexToByteArray(): ByteArray {
        return this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test
    fun `real BlueSolar MPPT 75-15 advert decodes correctly`() {
        // From victron-ble tests/test_solar_charger.py + devices/solar_charger.py
        val advert = "100242a0016207adceb37b605d7e0ee21b24df5c".hexToByteArray()
        val key = "adeccb947395801a4dd45a2eaa44bf17"

        val parsed = VictronParser.parseAdvertisement(
            mac = "AA:BB:CC:DD:EE:FF",
            manufacturerData = advert,
            rssi = -60,
            encryptionKeyHex = key
        )

        assertNotNull("Parser should succeed on real advert", parsed)
        assertEquals(0xA042, parsed!!.modelId) // BlueSolar MPPT 75/15
        assertEquals(1, parsed.recordType)     // Solar charger

        val data = parsed.data
        assertEquals("ABSORPTION", data["charge_state"])
        assertEquals(13.88, data["battery_voltage"] as Double, 0.01)
        assertEquals(1.4, data["battery_current"] as Double, 0.1)
        assertEquals(30, data["yield_today_wh"])
        assertEquals(19, data["solar_power_w"])
    }

    @Test
    fun `real SmartShunt advert decodes correctly`() {
        // From victron-ble tests/test_battery_monitor.py
        val advert = "100289a302b040af925d09a4d89aa0128bdef48c6298a9".hexToByteArray()
        val key = "aff4d0995b7d1e176c0c33ecb9e70dcd"

        val parsed = VictronParser.parseAdvertisement(
            mac = "11:22:33:44:55:66",
            manufacturerData = advert,
            rssi = -55,
            encryptionKeyHex = key
        )

        assertNotNull("Parser should succeed on real SmartShunt advert", parsed)
        assertEquals(2, parsed!!.recordType)

        val data = parsed.data
        assertEquals(12.53, data["battery_voltage"] as Double, 0.01)
        assertEquals(50.0, data["soc_percent"] as Double, 0.1)
        // Per reference convention, consumed is negative for discharge
        assertEquals(-50.0, data["consumed_ah"] as Double, 0.1)
        assertEquals(0, data["aux_mode"]) // typical for voltage aux in fixture
    }

    @Test
    fun `wrong encryption key is rejected`() {
        val advert = "100242a0016207adceb37b605d7e0ee21b24df5c".hexToByteArray()
        val wrongKey = "00000000000000000000000000000000"

        val parsed = VictronParser.parseAdvertisement(
            mac = "AA:BB:CC:DD:EE:FF",
            manufacturerData = advert,
            rssi = -60,
            encryptionKeyHex = wrongKey
        )

        assertEquals(null, parsed) // must be rejected by key check
    }
}
