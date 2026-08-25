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

    /** Value written to / read from REG_DEVICE_MODE. */
    const val MODE_CHARGER_ON = 0x01
    const val MODE_CHARGER_OFF = 0x04
    const val MODE_CHARGER_OFF_LEGACY = 0x00

    /** Battery / system-voltage settings (writable, see Mrkvak mppt_registers.json). */
    const val REG_BATTERY_VOLTAGE_SETTING = 0xEDEF // un8, V, 0..48  – "20V / 40V mode" in the UI
    const val REG_ABSORPTION_VOLTAGE = 0xEDF7 // un16, 0.01 V
    const val REG_FLOAT_VOLTAGE = 0xEDF6 // un16, 0.01 V
    const val REG_EQUALISATION_VOLTAGE = 0xEDF4 // un16, 0.01 V
    const val REG_CHARGER_VOLTAGE = 0xEDD5 // un16, 0.01 V – live read-only
    const val REG_PANEL_VOLTAGE = 0xEDBB // un16, 0.01 V – PV input; Instant Readout does not carry this

    /**
     * Session bootstrap, byte-for-byte from verified captures of what
     * VictronConnect sends (pysmartsolar / Mrkvak-victron-linux). Sending this
     * wakes the device's notification stream; without it the charger ignores
     * register commands.
     */
    val INIT_SEQUENCE: List<Pair<String, ByteArray>> = listOf(
        // 306b0002 (fa80ff) and the 06008218… blob make this SmartSolar drop GATT 19.
        SINGLE_UUID to hex("01"),
        SINGLE_UUID to hex("0300"),
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

    /** Alternate read used by some VictronConnect captures (0x82 vs 0x81). */
    fun makeReadFrame82(registerId: Int): ByteArray = byteArrayOf(
        0x05, 0x03, 0x82.toByte(), 0x19,
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

    /** Write frame for the battery system-voltage setting (register 0xEDEF, un8 volts). */
    fun makeBatteryVoltageSettingWriteFrame(volts: Int): ByteArray {
        require(volts in 0..48) { "battery_voltage_setting must be 0..48 V" }
        return makeWriteFrame(REG_BATTERY_VOLTAGE_SETTING, byteArrayOf(volts.toByte()))
    }

    /** Write frame for absorption / float / equalisation voltages (un16, 0.01 V, little-endian). */
    fun makeVoltageWriteFrame(registerId: Int, voltageVolts: Double): ByteArray {
        val raw = (voltageVolts * 100).toInt().coerceIn(0, 0xFFFF)
        return makeWriteFrame(registerId, byteArrayOf((raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte()))
    }

    /** Decode a little-endian u16 voltage value (scale 0.01 V). */
    fun decodeVoltage(raw: ByteArray): Double? {
        if (raw.size < 2) return null
        val u16 = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        return u16 / 100.0
    }

    /** Panel voltage in volts. 0xFFFF is the device NA (night / no PV), not 655.35 V. */
    fun panelVoltageOf(raw: ByteArray?): Double? {
        if (raw == null || raw.size < 2) return null
        val centivolts = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        return if (centivolts == 0xFFFF) null else centivolts / 100.0
    }

    /** Decode a u8 voltage setting (register 0xEDEF, volts as integer). */
    fun decodeBatteryVoltageSetting(raw: ByteArray): Int? {
        if (raw.isEmpty()) return null
        return raw[0].toInt() and 0xFF
    }

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
    fun parseRegisterValues(data: ByteArray): Map<Int, ByteArray> = parseRegisterStream(data).first

    /**
     * Same as [parseRegisterValues] but returns unconsumed tail bytes so split
     * notifications (Victron often splits 08… frames across packets) can be
     * stitched on the next notify.
     */
    fun parseRegisterStream(data: ByteArray): Pair<Map<Int, ByteArray>, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 6 <= data.size) {
            val start = indexOfFrame(data, pos) ?: return result to ByteArray(0)
            val category = data[start + 3].toInt() and 0xFF
            val command = data[start + 4].toInt() and 0xFF
            val lengthType = data[start + 5].toInt() and 0xFF
            val (length, valueStart) = when {
                lengthType == 0x58 -> {
                    if (start + 7 > data.size) return result to data.copyOfRange(start, data.size)
                    data[start + 6].toInt() and 0xFF to start + 7
                }
                lengthType == 0x50 -> 16 to start + 6
                else -> (lengthType and 0x0F) to start + 6
            }
            if (length <= 0 || valueStart + length > data.size) {
                return result to data.copyOfRange(start, data.size)
            }
            val registerId = (category shl 8) or command
            result[registerId] = data.copyOfRange(valueStart, valueStart + length)
            pos = valueStart + length
        }
        return result to ByteArray(0)
    }

    /** Device-mode value from parsed registers (null if not reported). */
    fun chargerModeOf(values: Map<Int, ByteArray>): Int? {
        val raw = values[REG_DEVICE_MODE] ?: return null
        return if (raw.isEmpty()) null else raw[0].toInt() and 0xFF
    }

    fun isChargerOn(mode: Int?): Boolean = mode == MODE_CHARGER_ON

    /** Stack queued the write and the device acknowledged GATT_SUCCESS (0). Status 133 is a failure now that writes are acknowledged. */
    fun gattWriteAccepted(queued: Boolean, status: Int): Boolean = queued && status == 0

    /** BluetoothGattCharacteristic.PROPERTY_WRITE */
    const val PROPERTY_WRITE = 0x08

    /** BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE */
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04

    /** BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT (acknowledged). */
    const val WRITE_TYPE_DEFAULT = 2

    /** BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE. */
    const val WRITE_TYPE_NO_RESPONSE = 1

    /**
     * Victron's control char (306b0002) is often props=22: READ | WRITE_NO_RESPONSE | NOTIFY
     * with no PROPERTY_WRITE. WRITE_TYPE_DEFAULT is refused immediately by the stack.
     * Prefer acknowledged writes when the char advertises them; otherwise no-response.
     */
    fun writeTypeForProperties(properties: Int): Int =
        when {
            properties and PROPERTY_WRITE != 0 -> WRITE_TYPE_DEFAULT
            properties and PROPERTY_WRITE_NO_RESPONSE != 0 -> WRITE_TYPE_NO_RESPONSE
            else -> WRITE_TYPE_DEFAULT
        }

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
