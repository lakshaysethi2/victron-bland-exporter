package com.lakshaysethi.victronbleexporter.diag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * End-to-end against a local mock HTTP server: verifies the diagnostics sender
 * POSTs a JSON body with device info + entries to the logs endpoint and treats a
 * 201 as success. (Tests stay server-independent; the live mppt-logs.lak.nz
 * endpoints are verified manually.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
// ConscryptMode OFF: Robolectric's bundled Conscrypt has no glibc-2.31-compatible aarch64 native
// on this arm64 Linux host (same pattern as TunnelUrlCopyShareTest).
@ConscryptMode(ConscryptMode.Mode.OFF)
class DiagnosticsSenderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class CapturedRequest(val requestLine: String, val body: String)

    /** Minimal single-request HTTP server; returns [statusLine] + [responseBody]. */
    private fun serveOnce(
        statusLine: String,
        responseBody: String,
        captured: AtomicReference<CapturedRequest>
    ): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                val socket = server.accept()
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val head = StringBuilder()
                    var line = reader.readLine()
                    var contentLength = 0
                    while (line != null && line.isNotBlank()) {
                        head.append(line).append('\n')
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        line = reader.readLine()
                    }
                    val body = if (contentLength > 0) CharArray(contentLength).also { reader.read(it) }.joinToString("") else ""
                    captured.set(CapturedRequest(head.toString(), body))
                    val resp = "$statusLine\r\nContent-Length: ${responseBody.toByteArray().size}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$responseBody"
                    s.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // client may have given up; test asserts on the captured request
            }
        }
        return server.localPort
    }

    @Test
    fun `sendLogs POSTs device info and entries and returns success on 201`() = runBlocking {
        AppLog.init(context)
        AppLog.clear()
        AppLog.flush()
        AppLog.i("boot")
        AppLog.e("ble failure")

        val captured = AtomicReference<CapturedRequest>()
        val port = serveOnce("HTTP/1.1 201 Created", """{"ok":true}""", captured)

        val result = Diagnostics.sendLogs(context, url = "http://127.0.0.1:$port/api/logs")
        Thread.sleep(500) // let the mock finish writing the request capture

        assertTrue("send should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val req = captured.get() ?: throw AssertionError("mock server received no request")
        assertTrue(req.requestLine.startsWith("POST /api/logs"))
        assertTrue(req.requestLine.contains("HTTP/1.1"))

        val body = req.body
        assertTrue(body.contains("\"device_id\":\""))
        assertTrue(body.contains("\"app_version\":\""))
        assertTrue(body.contains("\"entries\":["))
        assertTrue(body.contains("\"level\":\"info\""))
        assertTrue(body.contains("\"msg\":\"boot\""))
        assertTrue(body.contains("\"level\":\"error\""))
        assertTrue(body.contains("\"msg\":\"ble failure\""))

        AppLog.clear()
        AppLog.flush()
    }

    @Test
    fun `sendLogs surfaces non-2xx as failure without crashing`() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val port = serveOnce("HTTP/1.1 500 Internal Server Error", "oops", captured)

        val result = Diagnostics.sendLogs(context, url = "http://127.0.0.1:$port/api/logs")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `sendLogs fails gracefully when the server is unreachable`() = runBlocking {
        // Port 1 on loopback is closed on any sane host -> connection refused.
        val result = Diagnostics.sendLogs(context, url = "http://127.0.0.1:1/api/logs")
        assertTrue(result.isFailure)
    }

    @Test
    fun `httpPost round-trips the payload body`() {
        val captured = AtomicReference<CapturedRequest>()
        val port = serveOnce("HTTP/1.1 201 Created", """{"ok":true}""", captured)
        val response = Diagnostics.httpPost(
            "http://127.0.0.1:$port/api/logs",
            """{"device_id":"d1","entries":[]}"""
        )
        Thread.sleep(300)
        assertEquals("""{"ok":true}""", response)
        assertEquals("""{"device_id":"d1","entries":[]}""", captured.get()?.body)
    }
}
