package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory store of latest parsed Victron device metrics.
 * One entry per MAC address.
 */
object MetricsStore {

    private val latestData = ConcurrentHashMap<String, ParsedDevice>()

    fun update(parsed: ParsedDevice) {
        latestData[parsed.mac] = parsed
    }

    fun getAll(): Map<String, ParsedDevice> = latestData.toMap()

    fun get(mac: String): ParsedDevice? = latestData[mac]

    fun clear() {
        latestData.clear()
    }

    fun count(): Int = latestData.size
}