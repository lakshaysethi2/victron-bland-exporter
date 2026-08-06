package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory store of latest parsed Victron device metrics.
 * One entry per MAC address, with the epoch-millis timestamp of the last
 * broadcast that produced it (used by the exporter to expire stale devices).
 */
object MetricsStore {

    private val latestData = ConcurrentHashMap<String, ParsedDevice>()
    private val lastSeenMillis = ConcurrentHashMap<String, Long>()

    fun update(parsed: ParsedDevice, seenAt: Long = System.currentTimeMillis()) {
        latestData[parsed.mac] = parsed
        lastSeenMillis[parsed.mac] = seenAt
    }

    fun getAll(): Map<String, ParsedDevice> = latestData.toMap()

    /** Epoch millis of the last broadcast that updated [mac]; 0 if never seen. */
    fun lastSeenMillis(mac: String): Long = lastSeenMillis[mac] ?: 0L

    fun get(mac: String): ParsedDevice? = latestData[mac]

    fun clear() {
        latestData.clear()
        lastSeenMillis.clear()
    }

    fun count(): Int = latestData.size
}
