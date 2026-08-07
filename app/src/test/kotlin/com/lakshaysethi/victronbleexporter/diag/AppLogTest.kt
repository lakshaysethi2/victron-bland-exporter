package com.lakshaysethi.victronbleexporter.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the bounded log buffer (no Android types). */
class AppLogTest {

    @Test
    fun `log buffer keeps only the last 500 entries`() {
        val buffer = LogBuffer(AppLog.MAX_ENTRIES)
        for (i in 1..600) {
            buffer.append(LogEntry(ts = i.toLong(), level = "INFO", msg = "m$i"))
        }
        assertEquals(AppLog.MAX_ENTRIES, buffer.size())
        val snap = buffer.snapshot()
        assertEquals(AppLog.MAX_ENTRIES, snap.size)
        // Oldest 100 dropped, order preserved.
        assertEquals(101L, snap.first().ts)
        assertEquals("m101", snap.first().msg)
        assertEquals(600L, snap.last().ts)
        assertEquals("m600", snap.last().msg)
    }

    @Test
    fun `log buffer keeps everything when under the limit`() {
        val buffer = LogBuffer(500)
        buffer.append(LogEntry(1L, "INFO", "a"))
        buffer.append(LogEntry(2L, "WARN", "b"))
        buffer.append(LogEntry(3L, "ERROR", "c"))
        val snap = buffer.snapshot()
        assertEquals(3, snap.size)
        assertEquals(listOf("a", "b", "c"), snap.map { it.msg })
        assertEquals(listOf("INFO", "WARN", "ERROR"), snap.map { it.level })
        assertTrue(snap.all { it.ts > 0 })
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = LogBuffer(500)
        buffer.append(LogEntry(1L, "INFO", "a"))
        buffer.append(LogEntry(2L, "INFO", "b"))
        buffer.clear()
        assertEquals(0, buffer.size())
        assertEquals(emptyList<LogEntry>(), buffer.snapshot())
    }
}
