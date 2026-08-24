package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import com.lakshaysethi.victronbleexporter.parser.VictronParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks ALL Victron advertisements seen nearby, even if we don't have a key yet.
 * This powers the "easy discovery" UX - user sees nearby devices without typing MAC.
 */
data class DiscoveredDevice(
    val mac: String,
    val modelId: Int?,
    val modelName: String,
    val recordType: Int?,
    val rssi: Int,
    val lastSeenTimestamp: Long, // epoch millis
    val hasKey: Boolean,
    val parsed: ParsedDevice? = null,
    val needsKey: Boolean = !hasKey || parsed == null,
    val wrongKey: Boolean = false
)

object DiscoveredDevicesStore {

    private val devices = ConcurrentHashMap<String, DiscoveredDevice>()

    fun updateSeen(
        mac: String,
        modelId: Int?,
        recordType: Int?,
        rssi: Int,
        hasKey: Boolean,
        parsed: ParsedDevice?,
        decryptFailed: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val normalizedMac = mac.uppercase()
        val modelName = if (modelId != null) {
            try {
                VictronParser.getModelName(modelId)
            } catch (e: Exception) {
                "Victron-0x${modelId.toString(16).uppercase()}"
            }
        } else {
            "Victron Device"
        }

        val existing = devices[normalizedMac]
        // Keep strongest RSSI recent, but always update timestamp
        val mergedHasKey = hasKey || existing?.hasKey == true
        val bestParsed = parsed ?: existing?.parsed
        // Only a real decrypt attempt can claim wrong-key. The UI scanner never
        // parses, so it must not flip this just because parsed is still null.
        val wrongKey = when {
            bestParsed != null -> false
            decryptFailed && mergedHasKey -> true
            else -> existing?.wrongKey == true
        }

        devices[normalizedMac] = DiscoveredDevice(
            mac = normalizedMac,
            modelId = modelId ?: existing?.modelId,
            modelName = modelName,
            recordType = recordType ?: existing?.recordType,
            rssi = rssi, // always latest RSSI
            lastSeenTimestamp = now,
            hasKey = mergedHasKey,
            parsed = bestParsed,
            needsKey = !mergedHasKey || bestParsed == null,
            wrongKey = wrongKey
        )
    }

    fun markHasKey(mac: String, hasKey: Boolean = true) {
        val normalized = mac.uppercase()
        devices[normalized]?.let { existing ->
            devices[normalized] = existing.copy(
                hasKey = hasKey,
                needsKey = !hasKey || existing.parsed == null,
                wrongKey = false
            )
        }
    }

    fun getAll(): Map<String, DiscoveredDevice> = devices.toMap()

    fun getSortedByRssi(): List<DiscoveredDevice> =
        devices.values.sortedByDescending { it.rssi }

    fun get(mac: String): DiscoveredDevice? = devices[mac.uppercase()]

    fun clear() = devices.clear()

    fun timeAgo(millis: Long): String {
        val diffSec = (System.currentTimeMillis() - millis) / 1000
        return when {
            diffSec < 5 -> "just now"
            diffSec < 60 -> "${diffSec}s ago"
            diffSec < 3600 -> "${diffSec / 60}m ago"
            else -> "${diffSec / 3600}h ago"
        }
    }
}
