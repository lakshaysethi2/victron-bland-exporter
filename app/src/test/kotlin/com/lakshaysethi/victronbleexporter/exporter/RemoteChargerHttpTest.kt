package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.data.RemoteChargerStore
import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
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

    private class FakeVoltageSink : VoltageCommandSender {
        val battery = mutableListOf<Pair<String, Int>>()
        val charging = mutableListOf<Triple<String, Double?, Double?>>()
        val reads = mutableListOf<String>()
        override fun sendBatteryVoltageSetting(mac: String, volts: Int) { battery.add(mac to volts) }
        override fun sendChargingVoltages(mac: String, absorptionVolts: Double?, floatVolts: Double?) {
            charging.add(Triple(mac, absorptionVolts, floatVolts))
        }
        override fun requestVoltageRead(mac: String) { reads.add(mac) }
    }

    private class FakeKeySink : KeyCommandSender {
        val calls = mutableListOf<Pair<String, String>>()
        override fun saveKey(mac: String, key: String) { calls.add(mac to key) }
    }

    private class FakeReadSink : ChargerReadSender {
        val calls = mutableListOf<String>()
        override fun readCharger(mac: String) { calls.add(mac) }
    }

    private class FakeTunnelSink : TunnelCommandSender {
        val starts = mutableListOf<String?>()
        var stops = 0
        override fun start(token: String?) { starts.add(token) }
        override fun stop() { stops++ }
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
        val scheduleSink = FakeScheduleSink()
        val voltageSink = FakeVoltageSink()
        val keySink = FakeKeySink()
        val readSink = FakeReadSink()
        val tunnelSink = FakeTunnelSink()

        fun control(
            withKeySender: Boolean = true,
            withReadSender: Boolean = true,
            withTunnelSender: Boolean = true,
        ) = RemoteChargerHttp(
            settingsProvider = { RemoteChargerStore.RemoteChargerSettings(enabled = enabled, authSecret = secret) },
            statusProvider = { snapshot },
            macProvider = { mac },
            commandSender = sink,
            scheduleSender = scheduleSink,
            voltageCommandSender = voltageSink,
            keySender = if (withKeySender) keySink else null,
            readSender = if (withReadSender) readSink else null,
            tunnelSender = if (withTunnelSender) tunnelSink else null,
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
        assertEquals(404, c.handle("/charger/schedule", POST, headers(SECRET), """{"enabled":true,"enable":"08:30","disable":"18:00"}""").statusCode)
        assertEquals(404, c.handle("/voltage", GET, headers(SECRET), "").statusCode)
        assertEquals(404, c.handle("/voltage", POST, headers(SECRET), """{"battery_voltage_setting":24}""").statusCode)
        assertEquals(404, c.handle("/charger/key", POST, headers(SECRET), """{"mac":"AA:BB:CC:DD:EE:FF","key":"0123456789abcdef0123456789abcdef"}""").statusCode)
        assertEquals(404, c.handle("/charger", POST, headers(SECRET), """{"action":"read"}""").statusCode)
        assertEquals(404, c.handle("/charger/tunnel", POST, headers(SECRET), """{"action":"stop"}""").statusCode)
        assertEquals(404, c.handle("/charger/status", GET, emptyMap(), "").statusCode)
        assertTrue(h.sink.calls.isEmpty())
        assertTrue(h.scheduleSink.calls.isEmpty())
        assertTrue(h.voltageSink.battery.isEmpty())
        assertTrue(h.keySink.calls.isEmpty())
        assertTrue(h.readSink.calls.isEmpty())
        assertTrue(h.tunnelSink.starts.isEmpty())
    }

    @Test
    fun `blank secret hides every charger route`() {
        val h = Harness(enabled = true, secret = "")
        val c = h.control()
        assertEquals(404, c.handle("/charger/status", GET, headers("anything"), "").statusCode)
        assertEquals(404, c.handle("/charger", POST, headers("anything"), """{"action":"off"}""").statusCode)
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
        assertTrue(r.body.contains("\"scheduleEnabled\":false"))
        assertTrue(r.body.contains("\"enableTime\":\"08:30\""))
        assertTrue(r.body.contains("\"disableTime\":\"18:00\""))
        assertTrue(r.body.contains("\"live\":[]"))
        assertTrue(r.body.contains("\"debug\":[]"))
        assertTrue(r.body.contains("\"phoneTime\":\"\""))
        assertTrue(r.body.contains("\"phoneZone\":\"\""))
        assertTrue(r.body.contains("\"scheduleWantsOn\":false"))
        assertTrue(r.body.contains("\"nextTransition\":\"\""))
        assertTrue(r.body.contains("\"tunnelStatus\":\"\""))
        assertTrue(r.body.contains("\"tunnelUrl\":null"))
        assertTrue(r.body.contains("\"tunnelHasToken\":false"))
    }

    @Test
    fun `fromAppState copies live tunnel fields`() {
        val prevStatus = AppState.tunnelStatus
        val prevUrl = AppState.tunnelUrl
        try {
            AppState.tunnelStatus = "Running"
            AppState.tunnelUrl = "https://example.trycloudflare.com"
            val snap = ChargerStatusSnapshot.fromAppState()
            assertEquals("Running", snap.tunnelStatus)
            assertEquals("https://example.trycloudflare.com", snap.tunnelUrl)
            assertFalse(snap.tunnelHasToken)
        } finally {
            AppState.tunnelStatus = prevStatus
            AppState.tunnelUrl = prevUrl
        }
    }

    @Test
    fun `status includes tunnel status and url so LAN can see if the named tunnel is up`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(tunnelStatus = "Named tunnel running", tunnelUrl = "https://mppt.lak.nz")
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"tunnelStatus\":\"Named tunnel running\""))
        assertTrue(r.body.contains("\"tunnelUrl\":\"https://mppt.lak.nz\""))
    }

    @Test
    fun `status includes whether the window currently wants on and the next flip`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(scheduleEnabled = true, scheduleWantsOn = true, nextTransition = "18:00")
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"scheduleWantsOn\":true"))
        assertTrue(r.body.contains("\"nextTransition\":\"18:00\""))
    }

    @Test
    fun `status includes the phone clock the schedule uses`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(phoneTime = "15:42", phoneZone = "Pacific/Auckland")
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"phoneTime\":\"15:42\""))
        assertTrue(r.body.contains("\"phoneZone\":\"Pacific/Auckland\""))
    }

    @Test
    fun `status includes escaped charger debug lines`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(
            debug = listOf("12:00:00.000 Schedule tick -> charger ON"),
        )
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"debug\":[\"12:00:00.000 Schedule tick -> charger ON\"]"))
        assertEquals(20, ChargerStatusSnapshot.REMOTE_DEBUG_LINES)
    }

    @Test
    fun `status includes live instant readout`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(
            live = listOf(
                LiveReadout(
                    mac = "AA:BB:CC:DD:EE:FF",
                    model = "SmartSolar MPPT 150/35",
                    solarPowerW = 123,
                    batteryVoltage = 13.6,
                    batteryCurrent = 4.2,
                    socPercent = null,
                    lastSeen = 99L,
                ),
            ),
        )
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"solarPowerW\":123"))
        assertTrue(r.body.contains("\"batteryVoltage\":13.6"))
        assertTrue(r.body.contains("\"batteryCurrent\":4.2"))
        assertTrue(r.body.contains("\"model\":\"SmartSolar MPPT 150/35\""))
        assertFalse(r.body.contains("\"live\":[]"))
    }

    @Test
    fun `live readout omits stale devices and maps parser fields`() {
        MetricsStore.clear()
        MetricsStore.update(
            ParsedDevice(
                mac = "AA:BB:CC:DD:EE:FF",
                modelId = 0xA058,
                recordType = 1,
                data = mapOf(
                    "solar_power_w" to 80,
                    "battery_voltage" to 12.8,
                    "battery_current" to 1.5,
                    "soc_percent" to null,
                ),
                rssi = -60,
                lastSeen = 50_000L,
            ),
        )
        MetricsStore.update(
            ParsedDevice(
                mac = "11:22:33:44:55:66",
                modelId = 0xA042,
                recordType = 1,
                data = mapOf("solar_power_w" to 1),
                rssi = -70,
                lastSeen = 1L,
            ),
        )
        val live = LiveReadout.fromFreshMetrics(now = 100_000L)
        assertEquals(1, live.size)
        assertEquals("AA:BB:CC:DD:EE:FF", live[0].mac)
        assertEquals("SmartSolar MPPT 150/35", live[0].model)
        assertEquals(80, live[0].solarPowerW)
        assertEquals(12.8, live[0].batteryVoltage)
        assertEquals(1.5, live[0].batteryCurrent)
        MetricsStore.clear()
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
    fun `post uses body mac over the stored target`() {
        val h = Harness(mac = "AA:BB:CC:DD:EE:FF")
        val r = h.control().handle(
            "/charger", POST, headers(SECRET),
            """{"action":"off","mac":"11:22:33:44:55:66"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf(false to "11:22:33:44:55:66"), h.sink.calls)
    }

    @Test
    fun `post without stored mac falls back to the first live device`() {
        val h = Harness(mac = null)
        h.snapshot = h.snapshot.copy(
            mac = null,
            live = listOf(
                LiveReadout("DE:AD:BE:EF:00:01", "SmartSolar", 10, 12.8, 1.0, null, 1L),
                LiveReadout("DE:AD:BE:EF:00:02", "SmartShunt", null, 12.6, null, 80, 1L),
            ),
        )
        val r = h.control().handle("/charger", POST, headers(SECRET), """{"action":"on"}""")
        assertEquals(202, r.statusCode)
        assertEquals(listOf(true to "DE:AD:BE:EF:00:01"), h.sink.calls)
    }

    @Test
    fun `post with a malformed mac is rejected`() {
        val h = Harness()
        val r = h.control().handle(
            "/charger", POST, headers(SECRET),
            """{"action":"on","mac":"not-a-mac"}""",
        )
        assertEquals(400, r.statusCode)
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `post read accepted and forwarded without flipping`() {
        val h = Harness()
        val r = h.control().handle("/charger", POST, headers(SECRET), """{"action":"read"}""")
        assertEquals(202, r.statusCode)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), h.readSink.calls)
        assertTrue(h.sink.calls.isEmpty())
        assertTrue(r.body.contains("\"accepted\":true"))
        assertTrue(r.body.contains("\"action\":\"read\""))
    }

    @Test
    fun `post read uses body mac over the stored target`() {
        val h = Harness(mac = "AA:BB:CC:DD:EE:FF")
        val r = h.control().handle(
            "/charger", POST, headers(SECRET),
            """{"action":"read","mac":"11:22:33:44:55:66"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf("11:22:33:44:55:66"), h.readSink.calls)
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `post read without a sender returns 503`() {
        val h = Harness()
        val r = h.control(withReadSender = false).handle("/charger", POST, headers(SECRET), """{"action":"read"}""")
        assertEquals(503, r.statusCode)
        assertTrue(h.readSink.calls.isEmpty())
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `post schedule uses body mac when no charger is stored`() {
        val h = Harness(mac = null)
        val r = h.control().handle(
            "/charger/schedule", POST, headers(SECRET),
            """{"enabled":true,"enable":"09:00","disable":"17:30","mac":"aa:bb:cc:dd:ee:ff"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf(listOf(true, "09:00", "17:30", "AA:BB:CC:DD:EE:FF")), h.scheduleSink.calls)
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
        assertTrue(r.body.contains("Save schedule"))
        assertTrue(r.body.contains("/charger/schedule"))
        assertTrue(r.body.contains("/voltage"))
        assertTrue(r.body.contains("selectedMac"))
        assertTrue(r.body.contains("data-mac"))
        assertTrue(r.body.contains("schedFilled"))
        assertTrue(r.body.contains("phoneTime"))
        assertTrue(r.body.contains("scheduleWantsOn"))
        assertTrue(r.body.contains("nextTransition"))
        assertTrue(r.body.contains("tunnelStatus"))
        assertTrue(r.body.contains("tunnelUrl"))
        assertTrue(r.body.contains("/charger/key"))
        assertTrue(r.body.contains("Save key"))
        assertTrue(r.body.contains("Read state"))
        assertTrue(r.body.contains("send(\"read\")"))
        assertTrue(r.body.contains("/charger/tunnel"))
        assertTrue(r.body.contains("Save + start named"))
        assertTrue(r.body.contains("Start saved tunnel"))
        assertTrue(r.body.contains("token saved"))
        assertTrue(r.body.contains("sighted"))
        assertTrue(r.body.contains("wrong key"))
        assertFalse(r.body.contains(SECRET))
    }

    @Test
    fun `post schedule accepted and forwarded to the sender`() {
        val h = Harness()
        val r = h.control().handle(
            "/charger/schedule", POST, headers(SECRET),
            """{"enabled":true,"enable":"09:00","disable":"17:30"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf(listOf(true, "09:00", "17:30", "AA:BB:CC:DD:EE:FF")), h.scheduleSink.calls)
        assertTrue(h.sink.calls.isEmpty())
        assertTrue(r.body.contains("\"accepted\":true"))
        assertTrue(r.body.contains("\"enable\":\"09:00\""))
    }

    @Test
    fun `post schedule with bad times rejected`() {
        val h = Harness()
        val c = h.control()
        assertEquals(400, c.handle("/charger/schedule", POST, headers(SECRET), """{"enabled":true,"enable":"25:00","disable":"18:00"}""").statusCode)
        assertEquals(400, c.handle("/charger/schedule", POST, headers(SECRET), """{"enabled":true}""").statusCode)
        assertEquals(400, c.handle("/charger/schedule", POST, headers(SECRET), "").statusCode)
        assertTrue(h.scheduleSink.calls.isEmpty())
    }

    @Test
    fun `post schedule without configured charger mac returns 503`() {
        val h = Harness(mac = null)
        val r = h.control().handle(
            "/charger/schedule", POST, headers(SECRET),
            """{"enabled":false,"enable":"08:30","disable":"18:00"}""",
        )
        assertEquals(503, r.statusCode)
        assertTrue(h.scheduleSink.calls.isEmpty())
    }

    @Test
    fun `voltage page loads without a secret as a login shell`() {
        val r = Harness().control().handle(
            "/voltage", GET,
            mapOf("accept" to "text/html"),
            "",
        )
        assertEquals(200, r.statusCode)
        assertTrue(r.mimeType.contains("text/html"))
        assertTrue(r.body.contains("Voltage Settings"))
        assertFalse(r.body.contains(SECRET))
    }

    @Test
    fun `get voltage json requires the secret`() {
        val c = Harness().control()
        assertEquals(401, c.handle("/voltage", GET, emptyMap(), "").statusCode)
        assertEquals(401, c.handle("/voltage", GET, headers("wrong"), "").statusCode)
    }

    @Test
    fun `get voltage json reports settings and requests a read when empty`() {
        val previous = com.lakshaysethi.victronbleexporter.AppState.voltageSettings
        com.lakshaysethi.victronbleexporter.AppState.voltageSettings = null
        try {
            val h = Harness()
            val r = h.control().handle("/voltage", GET, headers(SECRET), "")
            assertEquals(200, r.statusCode)
            assertTrue(r.body.contains("\"battery_voltage_setting\":null"))
            assertTrue(r.body.contains("\"absorption_voltage\":null"))
            assertTrue(r.body.contains("\"panel_voltage\":null"))
            assertEquals(listOf("AA:BB:CC:DD:EE:FF"), h.voltageSink.reads)
        } finally {
            com.lakshaysethi.victronbleexporter.AppState.voltageSettings = previous
        }
    }

    @Test
    fun `post voltage battery setting is forwarded`() {
        val h = Harness()
        val r = h.control().handle(
            "/voltage", POST, headers(SECRET),
            """{"battery_voltage_setting":24}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF" to 24), h.voltageSink.battery)
        assertTrue(h.voltageSink.charging.isEmpty())
        assertTrue(h.sink.calls.isEmpty())
    }

    @Test
    fun `post voltage charging voltages are forwarded`() {
        val h = Harness()
        val r = h.control().handle(
            "/voltage", POST, headers(SECRET),
            """{"absorption_voltage":28.8,"float_voltage":27.6}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf(Triple("AA:BB:CC:DD:EE:FF", 28.8, 27.6)), h.voltageSink.charging)
        assertTrue(h.voltageSink.battery.isEmpty())
    }

    @Test
    fun `post voltage with bad body rejected`() {
        val h = Harness()
        val c = h.control()
        assertEquals(400, c.handle("/voltage", POST, headers(SECRET), "").statusCode)
        assertEquals(400, c.handle("/voltage", POST, headers(SECRET), """{\"nope\":1}""").statusCode)
        assertEquals(400, c.handle("/voltage", POST, headers(SECRET), """{\"battery_voltage_setting\":99}""").statusCode)
        assertTrue(h.voltageSink.battery.isEmpty())
        assertTrue(h.voltageSink.charging.isEmpty())
    }

    @Test
    fun `post voltage without configured charger mac returns 503`() {
        val h = Harness(mac = null)
        val r = h.control().handle("/voltage", POST, headers(SECRET), """{"battery_voltage_setting":24}""")
        assertEquals(503, r.statusCode)
        assertTrue(h.voltageSink.battery.isEmpty())
    }

    // ---- Instant Readout key ----

    private val HEX32 = "0123456789abcdef0123456789abcdef"

    @Test
    fun `status includes sighted devices without leaking a key`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(
            sighted = listOf(
                SightedDevice(
                    mac = "AA:BB:CC:DD:EE:FF",
                    model = "SmartSolar MPPT 150/35",
                    hasKey = false,
                    wrongKey = false,
                    lastSeen = 99L,
                    rssi = -70,
                ),
            ),
        )
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"sighted\":["))
        assertTrue(r.body.contains("\"hasKey\":false"))
        assertTrue(r.body.contains("\"wrongKey\":false"))
        assertTrue(r.body.contains("\"rssi\":-70"))
        assertFalse(r.body.contains(HEX32))
        assertFalse(r.body.contains("\"key\""))
    }

    @Test
    fun `post key accepted and forwarded without echoing the key`() {
        val h = Harness()
        val r = h.control().handle(
            "/charger/key", POST, headers(SECRET),
            """{"mac":"aa:bb:cc:dd:ee:ff","key":"$HEX32"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF" to HEX32), h.keySink.calls)
        assertTrue(r.body.contains("\"accepted\":true"))
        assertTrue(r.body.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(r.body.contains(HEX32))
    }

    @Test
    fun `post key accepts 12-hex mac without colons`() {
        val h = Harness()
        val r = h.control().handle(
            "/charger/key", POST, headers(SECRET),
            """{"mac":"aabbccddeeff","key":"$HEX32"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals("AA:BB:CC:DD:EE:FF", h.keySink.calls.single().first)
    }

    @Test
    fun `post key with short hex is rejected`() {
        val h = Harness()
        val r = h.control().handle(
            "/charger/key", POST, headers(SECRET),
            """{"mac":"AA:BB:CC:DD:EE:FF","key":"deadbeef"}""",
        )
        assertEquals(400, r.statusCode)
        assertTrue(h.keySink.calls.isEmpty())
        assertFalse(r.body.contains("deadbeef"))
    }

    @Test
    fun `post key without sender returns 503`() {
        val h = Harness()
        val r = h.control(withKeySender = false).handle(
            "/charger/key", POST, headers(SECRET),
            """{"mac":"AA:BB:CC:DD:EE:FF","key":"$HEX32"}""",
        )
        assertEquals(503, r.statusCode)
        assertTrue(h.keySink.calls.isEmpty())
    }

    @Test
    fun `post key requires the secret`() {
        val h = Harness()
        assertEquals(401, h.control().handle("/charger/key", POST, emptyMap(), """{"mac":"AA:BB:CC:DD:EE:FF","key":"$HEX32"}""").statusCode)
        assertTrue(h.keySink.calls.isEmpty())
    }

    @Test
    fun `sighted from store omits stale devices and never carries a key`() {
        DiscoveredDevicesStore.clear()
        try {
            DiscoveredDevicesStore.updateSeen(
                mac = "AA:BB:CC:DD:EE:FF",
                modelId = 0xA058,
                recordType = 1,
                rssi = -65,
                hasKey = false,
                parsed = null,
            )
            val fresh = SightedDevice.fromStore()
            assertEquals(1, fresh.size)
            assertEquals("AA:BB:CC:DD:EE:FF", fresh[0].mac)
            assertFalse(fresh[0].hasKey)
            assertFalse(fresh[0].toJson().contains("\"key\""))

            val stale = SightedDevice.fromStore(now = System.currentTimeMillis() + MetricsStore.FRESH_MS + 1)
            assertTrue(stale.isEmpty())
        } finally {
            DiscoveredDevicesStore.clear()
        }
    }

    // ---- named tunnel ----

    private val NAMED_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.remote"

    @Test
    fun `post tunnel token starts named tunnel and never echoes the token`() {
        val h = Harness()
        val r = h.control().handle(
            "/charger/tunnel", POST, headers(SECRET),
            """{"token":"$NAMED_TOKEN"}""",
        )
        assertEquals(202, r.statusCode)
        assertEquals(listOf(NAMED_TOKEN), h.tunnelSink.starts)
        assertEquals(0, h.tunnelSink.stops)
        assertTrue(r.body.contains("\"action\":\"start\""))
        assertFalse(r.body.contains(NAMED_TOKEN))
        assertFalse(r.body.contains("\"token\""))
    }

    @Test
    fun `post tunnel start uses the saved token and rejects when none is saved`() {
        val h = Harness()
        val missing = h.control().handle("/charger/tunnel", POST, headers(SECRET), """{"action":"start"}""")
        assertEquals(400, missing.statusCode)
        assertTrue(h.tunnelSink.starts.isEmpty())

        h.snapshot = h.snapshot.copy(tunnelHasToken = true)
        val r = h.control().handle("/charger/tunnel", POST, headers(SECRET), """{"action":"start"}""")
        assertEquals(202, r.statusCode)
        assertEquals(listOf<String?>(null), h.tunnelSink.starts)
    }

    @Test
    fun `post tunnel stop is forwarded`() {
        val h = Harness()
        val r = h.control().handle("/charger/tunnel", POST, headers(SECRET), """{"action":"stop"}""")
        assertEquals(202, r.statusCode)
        assertEquals(1, h.tunnelSink.stops)
        assertTrue(h.tunnelSink.starts.isEmpty())
        assertTrue(r.body.contains("\"action\":\"stop\""))
    }

    @Test
    fun `post tunnel short token is rejected`() {
        val h = Harness()
        val r = h.control().handle("/charger/tunnel", POST, headers(SECRET), """{"token":"short"}""")
        assertEquals(400, r.statusCode)
        assertTrue(h.tunnelSink.starts.isEmpty())
    }

    @Test
    fun `post tunnel without sender returns 503`() {
        val h = Harness()
        val r = h.control(withTunnelSender = false).handle(
            "/charger/tunnel", POST, headers(SECRET), """{"action":"stop"}""",
        )
        assertEquals(503, r.statusCode)
        assertEquals(0, h.tunnelSink.stops)
    }

    @Test
    fun `post tunnel requires the secret`() {
        val h = Harness()
        assertEquals(401, h.control().handle("/charger/tunnel", POST, emptyMap(), """{"action":"stop"}""").statusCode)
        assertEquals(0, h.tunnelSink.stops)
    }

    @Test
    fun `status reports whether a named-tunnel token is saved without echoing it`() {
        val h = Harness()
        h.snapshot = h.snapshot.copy(tunnelHasToken = true, tunnelStatus = "Stopped")
        val r = h.control().handle("/charger/status", GET, headers(SECRET), "")
        assertEquals(200, r.statusCode)
        assertTrue(r.body.contains("\"tunnelHasToken\":true"))
        assertFalse(r.body.contains(NAMED_TOKEN))
        assertFalse(r.body.contains("\"token\""))
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
}
