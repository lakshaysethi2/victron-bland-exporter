package com.lakshaysethi.victronbleexporter.exporter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-user-facing evidence for the stale-expiry rule: a device whose last
 * broadcast is older than the threshold keeps only `victron_last_seen_timestamp`
 * on /metrics — all frozen data metrics vanish (no-data means no-data) and
 * `victron_devices_total` counts only fresh devices.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class StaleDeviceMetricsTest {

    @After
    fun tearDown() {
        MetricsStore.clear()
    }

    private fun httpGet(port: Int): String {
        val conn = URL("http://127.0.0.1:$port/metrics").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return conn.inputStream.bufferedReader().readText()
    }

    private fun device(mac: String) = ParsedDevice(
        mac = mac,
        modelId = 0xA042,
        recordType = 1,
        data = mapOf(
            "device_type" to "mppt",
            "battery_voltage" to 12.5,
            "solar_power_w" to 324,
            "charge_state" to "ABSORPTION",
        ),
        rssi = -60,
    )

    @Test
    fun `stale device keeps only last_seen while fresh device keeps everything`() {
        val now = System.currentTimeMillis()
        MetricsStore.update(device("AA:BB:CC:DD:EE:FF"), seenAt = now) // fresh
        MetricsStore.update(device("11:22:33:44:55:66"), seenAt = now - 10 * 60_000L) // stale

        val exporter = PrometheusExporter(0)
        try {
            exporter.startServer()
            val metrics = httpGet(exporter.listeningPort)

            // Fresh device: data + liveness.
            assertTrue(metrics.contains("victron_battery_voltage_volts{device=\"BlueSolar MPPT 75/15\",mac=\"AA:BB:CC:DD:EE:FF\",type=\"mppt\"} 12.5"))
            assertTrue(metrics.contains("victron_solar_power_watts{device=\"BlueSolar MPPT 75/15\",mac=\"AA:BB:CC:DD:EE:FF\",type=\"mppt\"} 324.0"))
            assertTrue(metrics.contains("victron_last_seen_timestamp{device=\"BlueSolar MPPT 75/15\",mac=\"AA:BB:CC:DD:EE:FF\",type=\"mppt\"}"))

            // Stale device: only last_seen survives, with its real (old) timestamp.
            assertFalse(metrics.contains("victron_battery_voltage_volts{device=\"BlueSolar MPPT 75/15\",mac=\"11:22:33:44:55:66\",type=\"mppt\"} 12.5"))
            assertFalse(metrics.contains("victron_solar_power_watts{device=\"BlueSolar MPPT 75/15\",mac=\"11:22:33:44:55:66\",type=\"mppt\"} 324.0"))
            val staleLastSeenEpoch = (now - 10 * 60_000L) / 1000.0
            assertTrue(metrics.contains("victron_last_seen_timestamp{device=\"BlueSolar MPPT 75/15\",mac=\"11:22:33:44:55:66\",type=\"mppt\"} $staleLastSeenEpoch"))

            // Devices online counts only the fresh device.
            assertTrue(metrics.contains("victron_devices_total 1"))
        } finally {
            exporter.stop()
        }
    }
}
