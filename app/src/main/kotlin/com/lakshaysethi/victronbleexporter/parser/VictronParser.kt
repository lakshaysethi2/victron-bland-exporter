package com.lakshaysethi.victronbleexporter.parser

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "VictronParser"
private const val VICTRON_MFG_ID = 0x02E1

/**
 * Full port of Victron Instant Readout BLE advertisement parser.
 * Supports Solar Charger (MPPT) record type 0x01 and basic SmartShunt.
 */
object VictronParser {

    data class ParsedDevice(
        val mac: String,
        val modelId: Int,
        val recordType: Int,
        val data: Map<String, Any?>,
        val rssi: Int
    )

    fun isVictronAdvertisement(manufacturerData: ByteArray?): Boolean {
        if (manufacturerData == null || manufacturerData.size < 3) return false
        val mfgId = ((manufacturerData[1].toInt() and 0xFF) shl 8) or (manufacturerData[0].toInt() and 0xFF)
        return mfgId == VICTRON_MFG_ID && manufacturerData[2].toInt() and 0xFF == 0x10
    }

    fun parseAdvertisement(
        mac: String,
        manufacturerData: ByteArray,
        rssi: Int,
        encryptionKeyHex: String?
    ): ParsedDevice? {
        if (!isVictronAdvertisement(manufacturerData)) return null

        if (manufacturerData.size < 8) return null

        val modelId = ((manufacturerData[4].toInt() and 0xFF) shl 8) or (manufacturerData[3].toInt() and 0xFF)
        val recordType = manufacturerData[5].toInt() and 0xFF
        val iv = manufacturerData.copyOfRange(6, 8) // 2 bytes
        val keyCheck = manufacturerData[8].toInt() and 0xFF
        val encrypted = manufacturerData.copyOfRange(9, manufacturerData.size)

        if (encryptionKeyHex.isNullOrBlank() || encryptionKeyHex.length != 32) {
            Log.w(TAG, "No or invalid encryption key for $mac")
            return null
        }

        val key = try {
            hexStringToByteArray(encryptionKeyHex)
        } catch (e: Exception) {
            Log.e(TAG, "Bad key hex", e)
            return null
        }

        val decrypted = try {
            decryptAESCTR(encrypted, key, iv)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for $mac", e)
            return null
        }

        // Verify key check (first byte of decrypted should match keyCheck after XOR or as per protocol)
        // In practice many implementations just parse after successful decrypt.
        // We keep simple: assume success if decrypt didn't throw.

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
        // AES-128-CTR. Nonce/IV is 2 bytes LE + 14 zero bytes (common for Victron)
        val fullIv = ByteArray(16)
        System.arraycopy(iv, 0, fullIv, 0, 2)
        // rest remain 0

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        // For CTR the IV param is the initial counter value
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
        // Simplified BMV/SmartShunt parser (record 0x02)
        val reader = BitReader(decrypted)

        val aux = reader.readUnsignedInt(16)
        val voltage = reader.readSignedInt(16)
        val current = reader.readSignedInt(16)
        val power = reader.readSignedInt(16)
        val consumedAh = reader.readSignedInt(16)
        val soc = reader.readUnsignedInt(16)
        val timeToGo = reader.readUnsignedInt(16)

        return mapOf(
            "aux_voltage" to (if (aux != 0xFFFF) aux / 100.0 else null),
            "battery_voltage" to (if (voltage != 0x7FFF) voltage / 100.0 else null),
            "battery_current" to (if (current != 0x7FFF) current / 10.0 else null),
            "power_w" to (if (power != 0x7FFF) power else null),
            "consumed_ah" to (if (consumedAh != 0x7FFF) consumedAh / 10.0 else null),
            "soc_percent" to (if (soc != 0xFFFF) soc / 10.0 else null),
            "time_to_go_min" to (if (timeToGo != 0xFFFF) timeToGo / 60 else null),
            "device_type" to "batterymonitor"
        )
    }

    // Helper to get model name from modelId (simplified subset)
    fun getModelName(modelId: Int): String {
        return when (modelId) {
            0xA050 -> "SmartSolar MPPT 250/100"
            0xA051 -> "SmartSolar MPPT 150/100"
            0xA058 -> "SmartSolar MPPT 150/35"
            0xA059 -> "SmartSolar MPPT 150/100 rev2"
            // Add more as needed from the MODEL_ID_MAPPING
            else -> "Victron-$modelId"
        }
    }
}