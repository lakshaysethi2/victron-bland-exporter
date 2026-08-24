package com.lakshaysethi.victronbleexporter.exporter

import androidx.test.core.app.ApplicationProvider
import com.lakshaysethi.victronbleexporter.data.RemoteChargerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.HttpURLConnection
import java.net.URL

/**
 * Robolectric end-to-end test: a real PrometheusExporter (NanoHTTPD) is bound to
 * an ephemeral port and driven over loopback HTTP with the exact requests the
 * control page makes. A fake command sender stands in for the CHARGER_SET
 * intent -> ChargerController.setMode() BLE path, so the whole wiring (auth ->
 * routing -> JSON body -> sender) is verified without BLE hardware.
 *
 * ConscryptMode OFF: same reason as TunnelUrlCopyShareTest — Robolectric's
 * bundled Conscrypt has no glibc-compatible aarch64 native lib on this host, and
 * this test only needs plain HTTP over loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RemoteChargerHttpServerTest {

    private val appContext get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = RemoteChargerStore(appContext)
    private val sink = FakeSink()
    private val scheduleSink = FakeScheduleSink()
    private var server: PrometheusExporter? = null
    private var port = -1

    private class FakeSink : ChargerCommandSender {
        val calls = mutableListOf<Pair<Boolean, String>>()
        override fun sendChargerCommand(enable: Boolean, mac: String) {
            calls.add(enable to mac)
        }
    }

    private class FakeScheduleSink : ScheduleCommandSender {
        val calls = mutableListOf<List<Any>>()
        override fun saveSchedule(enabled: Boolean, enableTime: String, disableTime: String, mac: String) {
            calls.add(listOf(enabled, enableTime, disableTime, mac))
        }
    }

    @Before
    fun setUp() {
        store.save(true, "s3cret")
        val control = RemoteChargerHttp(
            settingsProvider = { store.load() },
            statusProvider = { ChargerStatusSnapshot.fromAppState() },
            macProvider = { "AA:BB:CC:DD:EE:FF" },
            commandSender = sink,
            scheduleSender = scheduleSink,
        )
        val exporter = PrometheusExporter(0, control)
        exporter.start(10_000, false)
        port = exporter.listeningPort
        assertTrue("server did not bind an ephemeral port", port > 0)
        server = exporter
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun request(method: String, path: String, secret: String?, body: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (secret != null) conn.setRequestProperty("X-Remote-Secret", secret)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    @Test
    fun `page requires no secret but status does`() {
        val (pageCode, page) = request("GET", "/charger", secret = null)
        assertEquals(200, pageCode)
        assertTrue(page.contains("ENABLE CHARGER"))
        assertTrue(page.contains("viewport"))

        val (statusCode, _) = request("GET", "/charger/status", secret = null)
        assertEquals(401, statusCode)
    }

    @Test
    fun `status with secret reports current charger mode`() {
        val (code, body) = request("GET", "/charger/status", secret = "s3cret")
        assertEquals(200, code)
        assertTrue(body.contains("\"mode\""))
    }

    @Test
    fun `post on with secret reaches the sender`() {
        val (code, body) = request("POST", "/charger", secret = "s3cret", body = """{"action":"on"}""")
        assertEquals(202, code)
        assertTrue(body.contains("\"accepted\":true"))
        assertEquals(listOf(true to "AA:BB:CC:DD:EE:FF"), sink.calls)
    }

    @Test
    fun `post off with secret reaches the sender`() {
        val (code, _) = request("POST", "/charger", secret = "s3cret", body = """{"action":"off"}""")
        assertEquals(202, code)
        assertEquals(listOf(false to "AA:BB:CC:DD:EE:FF"), sink.calls)
    }

    @Test
    fun `post without secret rejected and sender untouched`() {
        val (code, _) = request("POST", "/charger", secret = null, body = """{"action":"on"}""")
        assertEquals(401, code)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `post with bad body rejected and sender untouched`() {
        val (code, _) = request("POST", "/charger", secret = "s3cret", body = """{"action":"sideways"}""")
        assertEquals(400, code)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `wrong secret rejected`() {
        val (code, _) = request("GET", "/charger/status", secret = "not the secret")
        assertEquals(401, code)
    }

    @Test
    fun `post schedule with secret reaches the sender`() {
        val (code, body) = request(
            "POST", "/charger/schedule", secret = "s3cret",
            body = """{"enabled":true,"enable":"09:15","disable":"16:45"}""",
        )
        assertEquals(202, code)
        assertTrue(body.contains("\"accepted\":true"))
        assertEquals(listOf(listOf(true, "09:15", "16:45", "AA:BB:CC:DD:EE:FF")), scheduleSink.calls)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `existing metrics endpoints still work alongside charger routes`() {
        val (code, body) = request("GET", "/health", secret = null)
        assertEquals(200, code)
        assertTrue(body.contains("OK"))
        val (code404, _) = request("GET", "/metrics/charger", secret = null)
        assertEquals(404, code404)
    }
}
