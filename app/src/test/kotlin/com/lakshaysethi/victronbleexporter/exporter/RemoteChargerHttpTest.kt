package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.data.RemoteChargerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the remote charger-control HTTP surface: routing, constant-time
 * auth, JSON action parsing, and the command-sender seam. A fake sender stands
 * in for the service-intent path that ends in ChargerController.setMode() over
 * BLE (the captain verifies the real device flip after install).
 */
class RemoteChargerHttpTest {

    private class FakeSink : ChargerCommandSender {
        val calls = mutableListOf<Pair<Boolean, String>>()
        override fun sendChargerCommand(enable: Boolean, mac: String): Boolean {
            calls.add(enable to mac)
            return true
        }
    }

    private class FailingSink : ChargerCommandSender {
        override fun sendChargerCommand(enable: Boolean, mac: String): Boolean = false
    }

    private class Harness(
        var enabled: Boolean = true,
        var secret: String = "correct horse battery staple",
        var mac: String? = "AA:BB:CC:DD:EE:FF",
        var snapshot: ChargerStatusSnapshot = ChargerStatusSnapshot(
            mode = ChargerProtocol.MODE_CHARGER_ON,
            mac = "AA:BB:CC:DD:EE:FF",
            busy = false,
            lastAction = "Charger ENABLED (ON)",
            lastError = null,
            overrideUntil = 0L,
            stateUpdatedAt = 1234L,
        ),
    ) {
        val sink = FakeSink()

        fun control() = RemoteChargerHttp(
            settingsProvider = { RemoteChargerStore.RemoteChargerSettings(enabled = enabled, authSecret = secret) },
            statusProvider = { snapshot },
            macProvider = { mac },
            commandSender = sink,
        )
    }

    private val SECRET = "correct horse battery staple"
    private val GET = "GET"
    private val POST = "POST"

    private fun headers(secret: String?) =
        if (secret == null) emptyMap() else mapOf("x-remote-secret" to secret)

    // ---- feature flag / surface hiding ----

    @Test
    fun `feature disabled hides every charger route`() {
        val h = Harness(enabled = false)
        val c = h.control()
        assertEquals(404, c.handle("/charger", GET, headers(SECRET), "").statusCode)
        assertEquals(404, c.handle("/charger", GET, emptyMap(), "").statusCode)
        assertEquals(404, c.handle("/charger/status", GET, headers(SECRET), "").statusCode)
        assertEquals(404, c.handle("/charger", POST, headers(SECRET), """{"action":"on"}""").statusCode)
        assertEquals(404, c.handle("/charger/status", GET, emptyMap(), "").statusCode)
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `blank secret hides every charger route`() {
        val h = Harness(enabled = true, secret = "")
        val c = h.control()
        assertEquals(404, c.handle("/charger/status", GET, headers("anything"), "").statusCode)
        assertEquals(404, c.handle("/charger", POST, headers("anything"), """{"action":"off"}""").statusCode)
    }

    @Test
    fun `short secret hides every charger route`() {
        // The 8-char minimum is enforced server-side: a short stored secret is
        // treated like no secret at all, even if the UI predicate was bypassed.
        val h = Harness(secret = "short")
        val c = h.control()
        assertEquals(404, c.handle("/charger", GET, headers("short"), "").statusCode)
        assertEquals(404, c.handle("/charger/status", GET, headers("short"), "").statusCode)
        assertEquals(404, c.handle("/charger", POST, headers("short"), """{"action":"on"}""").statusCode)
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `non charger routes are never handled`() {
        val c = Harness().control()
        assertEquals(404, c.handle("/metrics", GET, headers(SECRET), "").statusCode)
        assertEquals(404, c.handle("/charger/extra", GET, headers(SECRET), "").statusCode)
        assertEquals(404, c.handle("/charger/status", POST, headers(SECRET), "").statusCode)
    }

    // ---- auth ----

    @Test
    fun `status requires the secret`() {
        val c = Harness().control()
        assertEquals(401, c.handle("/charger/status", GET, emptyMap(), "").statusCode)
        assertEquals(401, c.handle("/charger/status", GET, headers("wrong secret"), "").statusCode)
        assertEquals(401, c.handle("/charger/status", GET, mapOf("authorization" to "Basic dXNlcjpwYXNz"), "").statusCode)
    }

    @Test
    fun `unauthorized response carries www-authenticate header`() {
        val r = Harness().control().handle("/charger/status", GET, emptyMap(), "")
        assertEquals("Bearer", r.headers["WWW-Authenticate"])
    }

    @Test
    fun `status accepts x-remote-secret header and reports the mode`() {
        val c = Harness().control()
        val r = c.handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"mode\":\"ON\""))
        assertTrue(r.body.contains("\"mac\":\"AA:BB:CC:DD:EE:FF\""))
        assertTrue(r.body.contains("\"busy\":false"))
        assertTrue(r.body.contains("\"lastAction\":\"Charger ENABLED (ON)\""))
    }

    @Test
    fun `status accepts bearer authorization header`() {
        val c = Harness().control()
        val r = c.handle("/charger/status", GET, mapOf("authorization" to "Bearer $SECRET"), "")
        assertEquals(200, r.statusCode)
    }

    @Test
    fun `unknown mode renders as unknown`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(mode = null, mac = null, lastError = "boom")
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"mode\":null"))
        assertTrue(r.body.contains("\"mac\":null"))
        assertTrue(r.body.contains("\"lastError\":\"boom\""))
    }

    // ---- commands ----

    @Test
    fun `post on accepted and forwarded to the sender`() {
        val h = Harness()
        val r = h.control().handle("/charger", POST, headers(SECRET), """{"action":"on"}""")
        assertEquals(202, r.statusCode)
        assertEquals(listOf(true to "AA:BB:CC:DD:EE:FF"), h.sink.calls)
        assertTrue(r.body.contains("\"accepted\":true"))
        assertTrue(r.body.contains("\"action\":\"on\""))
    }

    @Test
    fun `post off accepted and forwarded to the sender`() {
        val h = Harness()
        val r = h.control().handle("/charger", POST, headers(SECRET), """{"action": "off"}""")
        assertEquals(202, r.statusCode)
        assertEquals(listOf(false to "AA:BB:CC:DD:EE:FF"), h.sink.calls)
    }

    @Test
    fun `post accepts uppercase action`() {
        val h = Harness()
        val r = h.control().handle("/charger", POST, headers(SECRET), """{"action":"ON"}""")
        assertEquals(202, r.statusCode)
        assertEquals(listOf(true to "AA:BB:CC:DD:EE:FF"), h.sink.calls)
    }

    @Test
    fun `post with bad body rejected without touching the sender`() {
        val h = Harness()
        val c = h.control()
        assertEquals(400, c.handle("/charger", POST, headers(SECRET), "").statusCode)
        assertEquals(400, c.handle("/charger", POST, headers(SECRET), "not json").statusCode)
        assertEquals(400, c.handle("/charger", POST, headers(SECRET), """{"action":"sideways"}""").statusCode)
        assertEquals(400, c.handle("/charger", POST, headers(SECRET), """{"nope":1}""").statusCode)
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `post without configured charger mac returns 503`() {
        val h = Harness(mac = null)
        val r = h.control().handle("/charger", POST, headers(SECRET), """{"action":"on"}""")
        assertEquals(503, r.statusCode)
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `post when sender fails returns 503`() {
        // A dropped/delivery-failed command must not look like optimistic success.
        val h = Harness()
        val c = RemoteChargerHttp(
            settingsProvider = { RemoteChargerStore.RemoteChargerSettings(enabled = true, authSecret = SECRET) },
            statusProvider = { h.snapshot },
            macProvider = { h.mac },
            commandSender = FailingSink(),
        )
        val r = c.handle("/charger", POST, headers(SECRET), """{"action":"on"}""")
        assertEquals(503, r.statusCode)
        assertTrue(r.body.contains("could not be sent"))
    }

    // ---- control page ----

    @Test
    fun `control page loads without a secret as a login shell`() {
        // A browser cannot attach custom headers to a navigation, so the page
        // shell must load unauthenticated; it is inert until the secret is entered.
        val c = Harness().control()
        val r = c.handle("/charger", GET, emptyMap(), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.mimeType.contains("text/html"))
        assertTrue(r.body.contains("viewport"))
        assertTrue(r.body.contains("ENABLE CHARGER"))
        assertTrue(r.body.contains("DISABLE CHARGER"))
        assertFalse(r.body.contains(SECRET))
        // The JS error string is set via textContent, which does not decode HTML
        // entities — it must use the real em dash, not &mdash; (kept in HTML markup only).
        assertTrue(r.body.contains("Wrong secret — enter it again."))
        assertFalse(r.body.contains("Wrong secret &mdash;"))
    }

    // ---- auth primitives ----

    @Test
    fun `constant time equals`() {
        assertTrue(RemoteChargerAuth.constantTimeEquals("abc", "abc"))
        assertFalse(RemoteChargerAuth.constantTimeEquals("abc", "abd"))
        assertFalse(RemoteChargerAuth.constantTimeEquals("abc", "abcd"))
        assertFalse(RemoteChargerAuth.constantTimeEquals("abc", ""))
        assertFalse(RemoteChargerAuth.constantTimeEquals(null, "abc"))
        assertFalse(RemoteChargerAuth.constantTimeEquals("abc", null))
        assertFalse(RemoteChargerAuth.constantTimeEquals(null, null))
    }

    @Test
    fun `secret extraction`() {
        assertNull(RemoteChargerAuth.extractSecret(emptyMap()))
        assertEquals("s", RemoteChargerAuth.extractSecret(mapOf("x-remote-secret" to "s")))
        assertEquals("tok", RemoteChargerAuth.extractSecret(mapOf("authorization" to "Bearer tok")))
        assertEquals("tok", RemoteChargerAuth.extractSecret(mapOf("authorization" to "bearer  tok")))
        assertNull(RemoteChargerAuth.extractSecret(mapOf("authorization" to "Basic abc")))
        assertNull(RemoteChargerAuth.extractSecret(mapOf("authorization" to "Bearer")))
        assertNull(RemoteChargerAuth.extractSecret(mapOf("x-remote-secret" to "  ")))
    }

    @Test
    fun `json escape handles control characters per rfc 8259`() {
        val jsonEscape = RemoteChargerHttpJson::escape
        assertEquals("plain text", jsonEscape("plain text"))
        assertEquals("a\\\"b", jsonEscape("a\"b"))
        assertEquals("a\\\\b", jsonEscape("a\\b"))
        // \b, \f and other control chars must be escaped, not emitted raw.
        assertEquals("a\\b\\f\\n\\u0000", jsonEscape("a\u0008\u000C\n\u0000"))
        assertEquals("\\u001f", jsonEscape("\u001F"))
    }
}
