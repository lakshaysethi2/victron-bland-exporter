package com.lakshaysethi.victronbleexporter.charger

import com.lakshaysethi.victronbleexporter.diag.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small thread-safe ring buffer of charger-control debug lines. The captain's
 * on-device tests are guided by this log: every BLE step (connect, init,
 * read/write frames, device response, errors) is appended so a failed write is
 * diagnosable from the Share Debug Logs output.
 *
 * Lines are also mirrored into [AppLog] (level CHARGER) so the remote
 * diagnostics payload captures the raw BLE exchange even across restarts.
 */
object ChargerDebugLog {

    private const val MAX_LINES = 200

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(message: String) {
        val line = "${timestampFormat.format(Date())} $message"
        AppLog.log("CHARGER", line)
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
