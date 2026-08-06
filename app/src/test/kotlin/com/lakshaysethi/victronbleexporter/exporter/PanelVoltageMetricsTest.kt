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
        AppState.panelVoltageLastError = null
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
            assertTrue(metrics.contains("victron_panel_voltage_state{device=\"AA:BB:CC:DD:EE:FF\"} 0"))

            // A fresh read that answers 0xFFFF (no panel voltage, e.g. at night) is a valid
            // success: the gauge is omitted (no value to serve) and state=3 says why, not an error.
            AppState.panelVoltageVolts = null
            AppState.panelVoltageLastError = null
            val na = httpGet(exporter.listeningPort)
            assertFalse(na.contains("victron_panel_voltage_volts{device=\"AA:BB:CC:DD:EE:FF\"}"))
            assertTrue(na.contains("victron_panel_voltage_state{device=\"AA:BB:CC:DD:EE:FF\"} 3"))

            // A value not refreshed within the TTL (MPPT offline) is stale: gauge omitted, state=4.
            AppState.panelVoltageVolts = 222.0
            AppState.panelVoltageUpdatedAt = System.currentTimeMillis() - AppState.PANEL_VOLTAGE_TTL_MS - 1000
            AppState.panelVoltageLastError = null
            val stale = httpGet(exporter.listeningPort)
            println("=====METRICS_STALE_LINES_BEGIN=====")
            stale.lines().filter { it.contains("panel_voltage") }.forEach { println(it) }
            println("=====METRICS_STALE_LINES_END=====")
            assertFalse(stale.contains("victron_panel_voltage_volts{device=\"AA:BB:CC:DD:EE:FF\"}"))
            assertTrue(stale.contains("victron_panel_voltage_state{device=\"AA:BB:CC:DD:EE:FF\"} 4"))

            // A failed read surfaces its reason remotely: state=2 with an error label.
            AppState.panelVoltageLastError = "connect timed out (panel-voltage)"
            val failed = httpGet(exporter.listeningPort)
            println("=====METRICS_FAILED_LINES_BEGIN=====")
            failed.lines().filter { it.contains("panel_voltage") }.forEach { println(it) }
            println("=====METRICS_FAILED_LINES_END=====")
            assertFalse(failed.contains("victron_panel_voltage_volts{device=\"AA:BB:CC:DD:EE:FF\"}"))
            assertTrue(failed.contains("victron_panel_voltage_state{device=\"AA:BB:CC:DD:EE:FF\",error=\"connect timed out (panel-voltage)\"} 2"))
        } finally {
            exporter.stop()
        }
    }

    private fun String.hexToByteArray(): ByteArray =
        this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
