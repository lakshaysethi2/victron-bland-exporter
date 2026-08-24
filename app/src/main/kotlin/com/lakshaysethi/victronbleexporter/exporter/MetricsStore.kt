package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory store of latest parsed Victron device metrics.
 * One entry per MAC address.
 */
object MetricsStore {

    /** Instant Readout ads are ~1 Hz; after this, live gauges are omitted. */
    const val FRESH_MS = 90_000L

    private val latestData = ConcurrentHashMap<String, ParsedDevice>()

    fun update(parsed: ParsedDevice) {
        latestData[parsed.mac] = parsed
    }

    fun getAll(): Map<String, ParsedDevice> = latestData.toMap()

    fun getFresh(now: Long = System.currentTimeMillis()): Map<String, ParsedDevice> =
        latestData.filterValues { isFresh(now, it.lastSeen) }

    fun get(mac: String): ParsedDevice? = latestData[mac]

    fun clear() {
        latestData.clear()
    }

    fun count(now: Long = System.currentTimeMillis()): Int = getFresh(now).size

    fun isFresh(now: Long, lastSeen: Long): Boolean =
        lastSeen > 0L && now - lastSeen < FRESH_MS
}