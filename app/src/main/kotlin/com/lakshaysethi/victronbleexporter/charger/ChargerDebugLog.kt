package com.lakshaysethi.victronbleexporter.charger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small thread-safe ring buffer of charger-control debug lines. The captain's
 * on-device tests are guided by this log: every BLE step (connect, init,
 * read/write frames, device response, errors) is appended so a failed write is
 * diagnosable from the Share Debug Logs output.
 */
object ChargerDebugLog {

    private const val MAX_LINES = 200

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(message: String) {
        val line = "${timestampFormat.format(Date())} $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
