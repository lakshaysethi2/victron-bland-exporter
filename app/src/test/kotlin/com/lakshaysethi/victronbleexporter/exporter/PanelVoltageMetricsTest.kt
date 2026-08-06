package com.lakshaysethi.victronbleexporter.exporter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-user-facing evidence for the panel-voltage feature: what an HTTP GET on
 * the real PrometheusExporter /metrics endpoint returns after a simulated
 * service tick decodes a real 0xEDBB notification (222.00 V). Runs the real
 * production code end to end (ChargerProtocol decode -> AppState cache ->
 * NanoHTTPD /metrics response).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PanelVoltageMetricsTest {

    @After
    fun tearDown() {
        AppState.chargerMac = null
        AppState.panelVoltageVolts = null
        AppState.panelVoltageUpdatedAt = 0L
    }

    private fun httpGet(port: Int): String {
        val conn = URL("http://127.0.0.1:$port/metrics").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return conn.inputStream.bufferedReader().readText()
    }

    @Test
    fun `metrics endpoint exposes the panel voltage gauge after a simulated service tick`() {
        // --- what the service's readPanelVoltageTick does with a real device notification ---
        val mac = "AA:BB:CC:DD:EE:FF"
        // Device notification for register 0xEDBB: 08 03 19 ed bb 42 b8 56 -> raw 0x56B8 = 222.00 V
        val parsed = ChargerProtocol.parseRegisterValues("080319edbb42b856".hexToByteArray())
        val volts = ChargerProtocol.panelVoltageOf(parsed[ChargerProtocol.REG_PANEL_VOLTAGE])
        assertEquals(222.0, volts!!, 0.001)
        AppState.chargerMac = mac
        AppState.panelVoltageVolts = volts
        AppState.panelVoltageUpdatedAt = System.currentTimeMillis()

        val exporter = PrometheusExporter(0) // ephemeral port
        try {
            exporter.startServer()
            val metrics = httpGet(exporter.listeningPort)
            println("=====METRICS_PANEL_LINES_BEGIN=====")
            metrics.lines().filter { it.contains("panel_voltage") }.forEach { println(it) }
            println("=====METRICS_PANEL_LINES_END=====")

            assertTrue(metrics.contains("victron_panel_voltage_volts{device=\"AA:BB:CC:DD:EE:FF\"} 222.0"))
            assertTrue(metrics.contains("# HELP victron_panel_voltage_volts"))
            assertTrue(metrics.contains("# TYPE victron_panel_voltage_volts gauge"))

            // Before any read (NA / not read yet), the gauge is absent from /metrics.
            AppState.panelVoltageVolts = null
            val fresh = httpGet(exporter.listeningPort)
            assertFalse(fresh.contains("victron_panel_voltage_volts{device="))
        } finally {
            exporter.stop()
        }
    }

    private fun String.hexToByteArray(): ByteArray =
        this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
