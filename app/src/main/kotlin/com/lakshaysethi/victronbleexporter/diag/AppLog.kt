package com.lakshaysethi.victronbleexporter.diag

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** One structured log entry (epoch millis, level, message). */
data class LogEntry(val ts: Long, val level: String, val msg: String)

/**
 * Pure bounded ring buffer of log entries. JVM-testable: no Android types.
 */
class LogBuffer(private val maxEntries: Int) {
    private val entries = ArrayDeque<LogEntry>()

    @Synchronized
    fun append(entry: LogEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * App-wide diagnostics log: a bounded buffer (last [MAX_ENTRIES] entries) that
 * survives process restarts via SharedPreferences. The charger BLE log lines are
 * mirrored in here (see [ChargerDebugLog]) so a "Send diagnostics" run captures
 * both app-level events and the raw BLE exchange for the captain's debugging.
 *
 * Persistence is throttled (~every 2s) so a burst of charger frames does not
 * hammer disk; [flush] forces a write (e.g. right before sending).
 */
object AppLog {

    const val MAX_ENTRIES = 500
    private const val PREFS_NAME = "victron_app_log"
    private const val KEY_ENTRIES = "entries"
    private const val PERSIST_INTERVAL_MS = 2_000L

    private val buffer = LogBuffer(MAX_ENTRIES)
    private var prefs: SharedPreferences? = null
    private var lastPersistAt = 0L

    /** Load persisted entries (first call); safe to call repeatedly. */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            reload()
        }
    }

    /** Simulate a process restart: re-read everything from disk. (Used by tests.) */
    internal fun reload() {
        val p = prefs ?: return
        synchronized(buffer) {
            buffer.clear()
            p.getString(KEY_ENTRIES, null)?.let { restore(it) }
        }
    }

    fun log(level: String, message: String) {
        buffer.append(LogEntry(System.currentTimeMillis(), level, message))
        persistIfDue()
    }

    fun i(message: String) = log("INFO", message)
    fun w(message: String) = log("WARN", message)
    fun e(message: String) = log("ERROR", message)

    fun snapshot(): List<LogEntry> = buffer.snapshot()

    fun clear() {
        buffer.clear()
        persist()
    }

    /** Force a persist now (e.g. right before sending diagnostics). */
    fun flush() = persist()

    private fun persistIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val sb = StringBuilder()
        sb.append('[')
        val entries = buffer.snapshot()
        for ((i, e) in entries.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"ts\":").append(e.ts)
                .append(",\"level\":").append(Json.str(e.level))
                .append(",\"msg\":").append(Json.str(e.msg)).append('}')
        }
        sb.append(']')
        p.edit().putString(KEY_ENTRIES, sb.toString()).apply()
        lastPersistAt = System.currentTimeMillis()
    }

    private fun restore(raw: String) {
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                buffer.append(
                    LogEntry(
                        ts = o.optLong("ts"),
                        level = o.optString("level", "INFO"),
                        msg = o.optString("msg", "")
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt/unreadable persisted log — start fresh.
        }
    }
}
