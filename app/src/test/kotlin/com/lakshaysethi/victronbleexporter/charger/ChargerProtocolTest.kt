package com.lakshaysethi.victronbleexporter.charger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerProtocolTest {

    private fun String.hexToByteArray(): ByteArray =
        this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `read frame matches verified reference`() {
        // Verified against Mrkvak/victron-linux test vector:
        // make_legacy_read_frame(0xEDF6).hex() == "05038119edf6"
        assertEquals("05038119edf6", ChargerProtocol.makeReadFrame(0xEDF6).toHex())
        assertEquals("050381190200", ChargerProtocol.makeReadFrame(0x0200).toHex())
    }

    @Test
    fun `write frame matches verified reference`() {
        // Verified against Mrkvak/victron-linux test vector:
        // make_legacy_write_frame(0xEDF6, bytes.fromhex("a005")).hex() == "06038219edf642a005"
        assertEquals(
            "06038219edf642a005",
            ChargerProtocol.makeWriteFrame(0xEDF6, "a005".hexToByteArray()).toHex(),
        )
        // Charger on -> register 0x0200 value 0x01, len byte 0x41
        assertEquals("0603821902004101", ChargerProtocol.makeChargerModeWriteFrame(true).toHex())
        // Charger off -> register 0x0200 value 0x04
        assertEquals("0603821902004104", ChargerProtocol.makeChargerModeWriteFrame(false).toHex())
    }

    @Test
    fun `write frame rejects oversized values`() {
        try {
            ChargerProtocol.makeWriteFrame(0x0200, ByteArray(16))
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `parses device mode notification`() {
        // Real capture: 08 03 19 02 00 41 04 -> register 0x0200, value 0x04 (charger off)
        val values = ChargerProtocol.parseRegisterValues("08031902004104".hexToByteArray())
        assertEquals(4, ChargerProtocol.chargerModeOf(values))
        assertFalse(ChargerProtocol.isChargerOn(4))
    }

    @Test
    fun `parses device mode notification for charger on`() {
        // Mrkvak/victron-linux test vector: 08031902004101 -> device_mode == 1
        val values = ChargerProtocol.parseRegisterValues("08031902004101".hexToByteArray())
        assertEquals(1, ChargerProtocol.chargerModeOf(values))
        assertTrue(ChargerProtocol.isChargerOn(1))
    }

    @Test
    fun `parses battery voltage notification`() {
        // Real capture: 08 03 19 ed 8d 42 35 05 -> register 0xED8D, 2 value bytes (13.33 V)
        val values = ChargerProtocol.parseRegisterValues("080319ed8d423505".hexToByteArray())
        val raw = values[0xED8D]!!
        assertEquals(2, raw.size)
        assertEquals(0x0535, ((raw[1].toInt() and 0xFF) shl 8) or (raw[0].toInt() and 0xFF))
    }

    @Test
    fun `split notification is completed on the next packet`() {
        val first = "080319020041".hexToByteArray()
        val (partial, leftover) = ChargerProtocol.parseRegisterStream(first)
        assertTrue(partial.isEmpty())
        assertEquals(6, leftover.size)
        val (full, rest) = ChargerProtocol.parseRegisterStream(leftover + "04".hexToByteArray())
        assertEquals(4, ChargerProtocol.chargerModeOf(full))
        assertEquals(0, rest.size)
    }

    @Test
    fun `parses multiple frames packed in one notification`() {
        // Two frames concatenated: device mode (off) + battery voltage
        val data = "08031902004104" + "080319ed8d423505"
        val values = ChargerProtocol.parseRegisterValues(data.hexToByteArray())
        assertEquals(2, values.size)
        assertEquals(4, ChargerProtocol.chargerModeOf(values))
    }

    @Test
    fun `unknown mode stays null`() {
        assertNull(ChargerProtocol.chargerModeOf(emptyMap()))
        assertEquals("Unknown", ChargerProtocol.chargerModeText(null))
    }

    @Test
    fun `init sequence contains expected bootstrap frames`() {
        val flat = ChargerProtocol.INIT_SEQUENCE.map { it.second.toHex() }
        assertTrue(flat.contains("01"))
        assertTrue(flat.contains("0300"))
        assertFalse(flat.contains("fa80ff"))
    }

    @Test
    fun `battery voltage setting write frame`() {
        // 0xEDEF is un8: value 0x18 -> "06038219edef4118" (len 0x41 = 1 byte)
        assertEquals("06038219edef4118", ChargerProtocol.makeBatteryVoltageSettingWriteFrame(24).toHex())
        assertEquals("06038219edef410c", ChargerProtocol.makeBatteryVoltageSettingWriteFrame(12).toHex())
    }

    @Test
    fun `voltage write frame little-endian 0_01 V`() {
        // 28.80 V -> 2880 = 0x0B40 -> LE a005? actually 2880 is 0x0B40 but our test uses 14.34-like via
        // makeVoltageWriteFrame: 12.34 V -> 1234 = 0x04D2 -> LE d204 with register 0xEDF7
        assertEquals("06038219edf742d204", ChargerProtocol.makeVoltageWriteFrame(0xEDF7, 12.34).toHex())
        // float 27.6 V -> 2760 = 0x0AC8 -> c80a
        assertEquals("06038219edf642c80a", ChargerProtocol.makeVoltageWriteFrame(0xEDF6, 27.6).toHex())
    }

    @Test
    fun `decode voltage helpers`() {
        // 0xED8D 2-byte LE value 0x0535 -> 13.33 V
        assertEquals(13.33, ChargerProtocol.decodeVoltage("3505".hexToByteArray())!!, 0.001)
        assertEquals(24, ChargerProtocol.decodeBatteryVoltageSetting("18".hexToByteArray()))
    }

    @Test
    fun `panel voltage register 0xEDBB is 0_01 V and 0xFFFF is NA`() {
        assertEquals(0xEDBB, ChargerProtocol.REG_PANEL_VOLTAGE)
        assertEquals("05038119edbb", ChargerProtocol.makeReadFrame(ChargerProtocol.REG_PANEL_VOLTAGE).toHex())
        // 222.00 V -> 22200 = 0x56B8 LE b856
        val values = ChargerProtocol.parseRegisterValues("080319edbb42b856".hexToByteArray())
        assertEquals(222.00, ChargerProtocol.panelVoltageOf(values[0xEDBB])!!, 0.001)
        assertNull(ChargerProtocol.panelVoltageOf("ffff".hexToByteArray()))
        assertNull(ChargerProtocol.panelVoltageOf(null))
        assertNull(ChargerProtocol.panelVoltageOf(byteArrayOf(0x00)))
    }

    @Test
    fun `readback must match the requested mode`() {
        assertTrue(ChargerProtocol.modeMatchesRequest(1, true))
        assertFalse(ChargerProtocol.modeMatchesRequest(1, false))
        assertTrue(ChargerProtocol.modeMatchesRequest(4, false))
        assertTrue(ChargerProtocol.modeMatchesRequest(0, false))
        assertFalse(ChargerProtocol.modeMatchesRequest(4, true))
        assertFalse(ChargerProtocol.modeMatchesRequest(0, true))
        assertFalse(ChargerProtocol.modeMatchesRequest(null, false))
        assertFalse(ChargerProtocol.modeMatchesRequest(2, false))
    }

    @Test
    fun `acknowledged GATT write only succeeds when queued and status 0`() {
        assertTrue(ChargerProtocol.gattWriteAccepted(true, 0))
        assertFalse(ChargerProtocol.gattWriteAccepted(false, 0))
        assertFalse(ChargerProtocol.gattWriteAccepted(true, 133))
        assertFalse(ChargerProtocol.gattWriteAccepted(true, 1))
    }

    @Test
    fun `control char props 22 uses WRITE_NO_RESPONSE`() {
        // READ (2) | WRITE_NO_RESPONSE (4) | NOTIFY (16) — real HQ2531JADNZ 306b0002
        assertEquals(ChargerProtocol.WRITE_TYPE_NO_RESPONSE, ChargerProtocol.writeTypeForProperties(22))
        assertEquals(ChargerProtocol.WRITE_TYPE_DEFAULT, ChargerProtocol.writeTypeForProperties(8))
        assertEquals(ChargerProtocol.WRITE_TYPE_DEFAULT, ChargerProtocol.writeTypeForProperties(12))
        assertEquals(ChargerProtocol.WRITE_TYPE_DEFAULT, ChargerProtocol.writeTypeForProperties(26))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
