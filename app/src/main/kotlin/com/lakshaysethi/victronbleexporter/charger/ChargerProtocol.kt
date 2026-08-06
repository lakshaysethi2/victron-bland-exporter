package com.lakshaysethi.victronbleexporter.charger

/**
 * Victron SmartSolar "legacy" VictronConnect BLE GATT protocol — the writable
 * control interface the MPPT exposes for charger on/off.
 *
 * ## How this was researched (captain's device: SmartSolar MPPT 150/45 rev3,
 * model id 0xA073, which advertises via the Instant Readout manufacturer data):
 *
 * The Instant Readout advertisements this app already parses are strictly
 * read-only. Charger control is done over a different GATT service:
 *
 *   Service:       306b0001-b081-4037-83dc-e59fcc3cdfd0  ("VE.Direct Smart" /
 *                  SmartSolar legacy VictronConnect protocol)
 *   Control char:  306b0002-...  keep-alive / poll commands (fa80ff, f980, f941)
 *   Single char:   306b0003-...  init sequence, register read + write frames
 *   Bulk char:     306b0004-...  bulk value reads
 *
 * Register 0x0200 (DEVICE_MODE, un8, read/write) controls the charger:
 *   1 = "Charger on", 0 or 4 = "Charger off" (all firmware accept both).
 * (Source: Victron "VE.Direct Protocol / BlueSolar and SmartSolar MPPT" Rev 18,
 *  "Device mode values (register 0x0200)"; and the mppt_registers.json metadata
 *  extracted from the VictronConnect APK: 0x0200 device_mode writable,
 *  enum {0: Charger off, 1: Charger on, 4: Charger off}.)
 *
 * Byte-level frame layouts cross-checked against three independent open-source
 * implementations that capture/talk to real devices:
 *  - trackIT-Systems/pysmartsolar (MPPT 100/50)
 *  - Olen/solar-monitor + Olen/VictronConnect (power on/off/eco on real HW)
 *  - Mrkvak/victron-linux (write settings with readback verification)
 *
 *   Read frame  (host -> 306b0003): 05 03 81 19 <reg hi> <reg lo>
 *   Write frame (host -> 306b0003): 06 03 82 19 <reg hi> <reg lo> <0x40+len> <value>
 *   Notification (device -> host):  08 03 19 <reg hi> <reg lo> <len> <value...>
 *
 * Pairing: the device requires bonding. The PIN is printed on the product
 * sticker if present; otherwise it is commonly 000000. On Android the system
 * pairing dialog appears automatically on first connect.
 */
object ChargerProtocol {

    const val SERVICE_UUID = "306b0001-b081-4037-83dc-e59fcc3cdfd0"
    const val CONTROL_UUID = "306b0002-b081-4037-83dc-e59fcc3cdfd0"
    const val SINGLE_UUID = "306b0003-b081-4037-83dc-e59fcc3cdfd0"
    const val BULK_UUID = "306b0004-b081-4037-83dc-e59fcc3cdfd0"

    /** Register that holds the charger mode (device mode). */
    const val REG_DEVICE_MODE = 0x0200

    /** Solar panel (PV) input voltage in 0.01 V (VE_REG_DC_INPUT_VOLTAGE). */
    const val REG_PANEL_VOLTAGE = 0xEDBB

    /** Value written to / read from REG_DEVICE_MODE. */
    const val MODE_CHARGER_ON = 0x01
    const val MODE_CHARGER_OFF = 0x04
    const val MODE_CHARGER_OFF_LEGACY = 0x00

    /**
     * Session bootstrap, byte-for-byte from verified captures of what
     * VictronConnect sends (pysmartsolar / Mrkvak-victron-linux). Sending this
     * wakes the device's notification stream; without it the charger ignores
     * register commands.
     */
    val INIT_SEQUENCE: List<Pair<String, ByteArray>> = listOf(
        CONTROL_UUID to hex("fa80ff"),
        CONTROL_UUID to hex("f980"),
        SINGLE_UUID to hex("01"),
        SINGLE_UUID to hex("0300"),
        SINGLE_UUID to hex("060082189342102703010303"),
        BULK_UUID to hex("05008119ec0f05008119ec0e05008119010c0500"),
        SINGLE_UUID to hex("81189005008119ec3f05008119ec12"),
        SINGLE_UUID to hex("19ecdc05038119eceb05038119eced"),
        CONTROL_UUID to hex("f941"),
        SINGLE_UUID to hex("0600821893421027"),
        CONTROL_UUID to hex("f941"),
    )

    /** Shorter handshake used to start a control session. */
    val SESSION_HANDSHAKE: List<Pair<String, ByteArray>> = listOf(
        CONTROL_UUID to hex("fa80ff"),
        CONTROL_UUID to hex("f980"),
        SINGLE_UUID to hex("01"),
        SINGLE_UUID to hex("0300"),
        SINGLE_UUID to hex("060082189342102703010303"),
    )

    /** Poll command that makes the device stream live status values. */
    fun pollFrame(): ByteArray = hex("f941")

    /** Build a register read frame: 05 03 81 19 <reg hi> <reg lo>. */
    fun makeReadFrame(registerId: Int): ByteArray = byteArrayOf(
        0x05, 0x03, 0x81.toByte(), 0x19,
        ((registerId shr 8) and 0xFF).toByte(),
        (registerId and 0xFF).toByte(),
    )

    /**
     * Build a register write frame: 06 03 82 19 <reg hi> <reg lo> <0x40+len> <value>.
     * The <0x40+len> prefix is the same "length byte" the device uses in its
     * own notification frames (0x41 = 1 value byte, 0x42 = 2, ...).
     */
    fun makeWriteFrame(registerId: Int, value: ByteArray): ByteArray {
        require(value.isNotEmpty() && value.size <= 15) { "legacy writes support 1..15 value bytes" }
        return byteArrayOf(
            0x06, 0x03, 0x82.toByte(), 0x19,
            ((registerId shr 8) and 0xFF).toByte(),
            (registerId and 0xFF).toByte(),
            (0x40 + value.size).toByte(),
        ) + value
    }

    /** Frame for "set charger on/off" (register 0x0200). */
    fun makeChargerModeWriteFrame(on: Boolean): ByteArray =
        makeWriteFrame(REG_DEVICE_MODE, byteArrayOf((if (on) MODE_CHARGER_ON else MODE_CHARGER_OFF).toByte()))

    /**
     * Parse raw notification bytes into registerId -> value bytes.
     *
     * Frame layout (from real captures, e.g. battery voltage):
     *   08 03 19 ed 8d 42 35 05  -> register 0xED8D, len byte 0x42 => 2 value bytes 35 05
     *   08 03 19 02 00 41 04     -> register 0x0200 (device mode), len byte 0x41 => 1 value byte 04
     *
     * Length byte: 0x40..0x4F = that many value bytes; 0x50 = 16; 0x58 = extended
     * (next byte is the length). Multiple frames can be packed into one packet.
     */
    fun parseRegisterValues(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 6 <= data.size) {
            val start = indexOfFrame(data, pos) ?: break
            val category = data[start + 3].toInt() and 0xFF
            val command = data[start + 4].toInt() and 0xFF
            val lengthType = data[start + 5].toInt() and 0xFF
            val (length, valueStart) = when {
                lengthType == 0x58 -> {
                    if (start + 7 > data.size) return result
                    data[start + 6].toInt() and 0xFF to start + 7
                }
                lengthType == 0x50 -> 16 to start + 6
                else -> (lengthType and 0x0F) to start + 6
            }
            if (length <= 0 || valueStart + length > data.size) return result
            val registerId = (category shl 8) or command
            result[registerId] = data.copyOfRange(valueStart, valueStart + length)
            pos = valueStart + length
        }
        return result
    }

    /** Panel voltage in volts from the register value bytes (null when not reported / NA 0xFFFF). */
    fun panelVoltageOf(raw: ByteArray?): Double? {
        if (raw == null || raw.size < 2) return null
        val centivolts = ((raw[1].toInt() and 0xFF) shl 8) or (raw[0].toInt() and 0xFF)
        return if (centivolts == 0xFFFF) null else centivolts / 100.0
    }

    /** Device-mode value from parsed registers (null if not reported). */
    fun chargerModeOf(values: Map<Int, ByteArray>): Int? {
        val raw = values[REG_DEVICE_MODE] ?: return null
        return if (raw.isEmpty()) null else raw[0].toInt() and 0xFF
    }

    fun isChargerOn(mode: Int?): Boolean = mode == MODE_CHARGER_ON

    /** True when [mode] satisfies a request to set the charger to [on] (1 = on, 0/4 = off). */
    fun modeMatchesRequest(mode: Int?, on: Boolean): Boolean = when (mode) {
        MODE_CHARGER_ON -> on
        MODE_CHARGER_OFF, MODE_CHARGER_OFF_LEGACY -> !on
        else -> false
    }

    fun chargerModeText(mode: Int?): String = when (mode) {
        MODE_CHARGER_ON -> "ON"
        MODE_CHARGER_OFF, MODE_CHARGER_OFF_LEGACY -> "OFF"
        else -> "Unknown"
    }

    private fun indexOfFrame(data: ByteArray, from: Int): Int? {
        var i = from
        while (i + 6 <= data.size) {
            if (data[i] == 0x08.toByte() &&
                data[i + 1] == 0x03.toByte() &&
                data[i + 2] == 0x19.toByte()
            ) {
                return i
            }
            i++
        }
        return null
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
