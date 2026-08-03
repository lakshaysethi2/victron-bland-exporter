package com.lakshaysethi.victronbleexporter.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the POST payload builder (device info + entries). */
class DiagnosticsPayloadTest {

    private val entries = listOf(
        LogEntry(ts = 1000L, level = "INFO", msg = "hello"),
        LogEntry(ts = 2000L, level = "ERROR", msg = "boom")
    )

    private fun build(
        entries: List<LogEntry> = this.entries,
        tunnelStatus: String = "Connected",
        chargerState: String = "ON"
    ): String = Diagnostics.buildPayload(
        deviceId = "dev-123",
        appVersion = "0.1.0",
        deviceModel = "Pixel 6",
        androidVersion = "14 (API 34)",
        uptimeMillis = 42_000L,
        entries = entries,
        tunnelStatus = tunnelStatus,
        chargerState = chargerState
    )

    @Test
    fun `payload contains device info, status and entries`() {
        val payload = build()
        assertTrue(payload.contains("\"device_id\":\"dev-123\""))
        assertTrue(payload.contains("\"app_version\":\"0.1.0\""))
        assertTrue(payload.contains("\"device_model\":\"Pixel 6\""))
        assertTrue(payload.contains("\"android_version\":\"14 (API 34)\""))
        assertTrue(payload.contains("\"uptime_ms\":42000"))
        assertTrue(payload.contains("\"tunnel_status\":\"Connected\""))
        assertTrue(payload.contains("\"charger_state\":\"ON\""))
        assertTrue(payload.contains("\"ts\":1000"))
        assertTrue(payload.contains("\"level\":\"INFO\""))
        assertTrue(payload.contains("\"msg\":\"hello\""))
        assertTrue(payload.contains("\"ts\":2000"))
        assertTrue(payload.contains("\"level\":\"ERROR\""))
        assertTrue(payload.contains("\"msg\":\"boom\""))
        assertTrue(payload.startsWith("{"))
        assertTrue(payload.endsWith("}"))
    }

    @Test
    fun `payload escapes quotes and newlines in messages`() {
        val payload = build(entries = listOf(LogEntry(ts = 1L, level = "INFO", msg = "say \"hi\"\nnext")))
        assertTrue(payload.contains("say \\\"hi\\\"\\nnext"))
        assertFalse(payload.contains("say \"hi\"\nnext"))
    }

    @Test
    fun `empty entries produce an empty entries array`() {
        val payload = build(entries = emptyList())
        assertTrue(payload.contains("\"entries\":[]"))
    }

    @Test
    fun `control chars are escaped`() {
        val payload = build(entries = listOf(LogEntry(ts = 1L, level = "INFO", msg = "tab\there")))
        assertTrue(payload.contains("tab\\there") || payload.contains("tab\\u0009here"))
    }
}
