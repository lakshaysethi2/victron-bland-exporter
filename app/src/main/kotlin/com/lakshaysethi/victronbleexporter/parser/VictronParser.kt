package com.lakshaysethi.victronbleexporter.parser

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Parsed device data returned by the parser.
 */
data class ParsedDevice(
    val mac: String,
    val modelId: Int,
    val recordType: Int,
    val data: Map<String, Any?>,
    val rssi: Int
)

object VictronParser {

    fun isVictronAdvertisement(manufacturerData: ByteArray?): Boolean {
        if (manufacturerData == null || manufacturerData.size < 2) return false
        // After Android strips the company ID (real captures always have 0x02 here):
        // [0]=0x10 (prefix), [1]=0x02 (record protocol/version), [2-3]=model LE, ...
        return (manufacturerData[0].toInt() and 0xFF) == 0x10 &&
               (manufacturerData[1].toInt() and 0xFF) == 0x02
    }

    fun parseAdvertisement(
        mac: String,
        manufacturerData: ByteArray,
        rssi: Int,
        encryptionKeyHex: String?
    ): ParsedDevice? {
        if (!isVictronAdvertisement(manufacturerData)) return null

        if (manufacturerData.size < 8) return null

        // Correct layout (company ID stripped by Android):
        // [0]=0x10, [1]=0x02 (protocol), [2-3]=model LE, [4]=recordType,
        // [5-6]=IV LE, [7]=keyCheck, [8+]=encrypted
        val modelId = ((manufacturerData[3].toInt() and 0xFF) shl 8) or (manufacturerData[2].toInt() and 0xFF)
        val recordType = manufacturerData[4].toInt() and 0xFF
        val iv = manufacturerData.copyOfRange(5, 7)
        val keyCheck = manufacturerData[7].toInt() and 0xFF
        val encrypted = manufacturerData.copyOfRange(8, manufacturerData.size)

        if (encryptionKeyHex.isNullOrBlank() || encryptionKeyHex.length != 32) {
            return null
        }

        val key = try {
            hexStringToByteArray(encryptionKeyHex)
        } catch (e: Exception) {
            return null
        }

        // Enforce key check BEFORE decrypting (critical security/ correctness check)
        if (keyCheck != (key[0].toInt() and 0xFF)) {
            return null
        }

        val decrypted = try {
            decryptAESCTR(encrypted, key, iv)
        } catch (e: Exception) {
            return null
        }

        val parsedData = when (recordType) {
            0x01 -> parseSolarCharger(decrypted)
            0x02 -> parseBatteryMonitor(decrypted)
            else -> mapOf("raw" to decrypted.joinToString("") { "%02x".format(it) })
        }

        return ParsedDevice(
            mac = mac,
            modelId = modelId,
            recordType = recordType,
            data = parsedData,
            rssi = rssi
        )
    }

    private fun decryptAESCTR(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val fullIv = ByteArray(16)
        System.arraycopy(iv, 0, fullIv, 0, 2)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(fullIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        return cipher.doFinal(ciphertext)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun parseSolarCharger(decrypted: ByteArray): Map<String, Any?> {
        val reader = BitReader(decrypted)

        val chargeState = reader.readUnsignedInt(8)
        val chargerError = reader.readUnsignedInt(8)
        val batteryVoltage = reader.readSignedInt(16)
        val batteryCurrent = reader.readSignedInt(16)
        val yieldToday = reader.readUnsignedInt(16)
        val solarPower = reader.readUnsignedInt(16)
        val loadCurrent = reader.readUnsignedInt(9)

        return mapOf(
            "charge_state" to (if (chargeState != 0xFF) OperationMode.fromValue(chargeState)?.name ?: chargeState else null),
            "charger_error" to (if (chargerError != 0xFF) ChargerError.fromValue(chargerError)?.name ?: chargerError else null),
            "battery_voltage" to (if (batteryVoltage != 0x7FFF) batteryVoltage / 100.0 else null),
            "battery_current" to (if (batteryCurrent != 0x7FFF) batteryCurrent / 10.0 else null),
            "yield_today_wh" to (if (yieldToday != 0xFFFF) yieldToday * 10 else null),
            "solar_power_w" to (if (solarPower != 0xFFFF) solarPower else null),
            "load_current_a" to (if (loadCurrent != 0x1FF) loadCurrent / 10.0 else null),
            "device_type" to "mppt"
        )
    }

    private fun parseBatteryMonitor(decrypted: ByteArray): Map<String, Any?> {
        // Real SmartShunt/BMV layout (from keshavdv/victron-ble battery_monitor.py)
        // ttg u16, voltage s16, alarm u16, aux u16, aux_mode u2, current s22, consumed u20, soc u10
        val reader = BitReader(decrypted)

        val timeToGo = reader.readUnsignedInt(16)
        val voltage = reader.readSignedInt(16)
        val alarm = reader.readUnsignedInt(16)
        val aux = reader.readUnsignedInt(16)
        val auxMode = reader.readUnsignedInt(2)

        // Current: read as unsigned 22-bit first for proper NA detection (all-ones = 0x3FFFFF)
        val rawCurrent = reader.readUnsignedInt(22)
        val current = if (rawCurrent == 0x3FFFFF) 0x7FFFFF else rawCurrent  // treat all-ones as NA
        val signedCurrent = if (current == 0x7FFFFF) null else {
            val signBit = 1 shl 21
            if (current and signBit != 0) (current - (1 shl 22)) else current
        }

        val consumedAhRaw = reader.readUnsignedInt(20)
        val consumedAh = if (consumedAhRaw == 0xFFFFF) null else consumedAhRaw

        val soc = reader.readUnsignedInt(10)

        return mapOf(
            "time_to_go_min" to (if (timeToGo != 0xFFFF) timeToGo else null),
            "battery_voltage" to (if (voltage != 0x7FFF) voltage / 100.0 else null),
            "alarm" to alarm,
            "aux" to aux,
            "aux_mode" to auxMode,
            "battery_current" to (signedCurrent?.let { it / 1000.0 }),
            // Discharge is negative per VE.Direct / reference convention
            "consumed_ah" to (consumedAh?.let { -it / 10.0 }),
            "soc_percent" to (if (soc != 0x3FF) soc / 10.0 else null),
            "device_type" to "batterymonitor"
        )
    }

    fun getModelName(modelId: Int): String {
        return when (modelId) {
            0xA042 -> "BlueSolar MPPT 75/15"
            0xA050 -> "SmartSolar MPPT 250/100"
            0xA051 -> "SmartSolar MPPT 150/100"
            0xA058 -> "SmartSolar MPPT 150/35"
            0xA059 -> "SmartSolar MPPT 150/100 rev2"
            else -> "Victron-0x${modelId.toString(16).uppercase()}"
        }
    }
}
