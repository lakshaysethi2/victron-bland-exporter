package com.lakshaysethi.victronbleexporter.exporter

import android.util.Log
import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

private const val TAG = "PrometheusExporter"
private const val DEFAULT_PORT = 5338

/**
 * Tiny embedded Prometheus exporter using NanoHTTPD.
 * Serves /metrics in Prometheus text format.
 */
class PrometheusExporter(
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/metrics" -> serveMetrics()
            "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK\n")
            "/devices" -> serveDevicesJson()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found\n")
        }
    }

    private fun serveMetrics(): Response {
        val sb = StringBuilder()
        val all = MetricsStore.getAll()

        sb.append("# HELP victron_devices_total Number of Victron devices discovered\n")
        sb.append("# TYPE victron_devices_total gauge\n")
        sb.append("victron_devices_total ${all.size}\n\n")

        for ((mac, device) in all) {
            val labels = buildLabels(mac, device)
            val data = device.data

            appendMetric(sb, "victron_battery_voltage_volts", labels, data["battery_voltage"] as? Number)
            appendMetric(sb, "victron_battery_current_amps", labels, data["battery_current"] as? Number)
            appendMetric(sb, "victron_solar_power_watts", labels, data["solar_power_w"] as? Number)
            appendMetric(sb, "victron_yield_today_wh", labels, data["yield_today_wh"] as? Number)
            appendMetric(sb, "victron_load_current_amps", labels, data["load_current_a"] as? Number)

            // Common
            appendMetric(sb, "victron_rssi_dbm", labels, device.rssi.toDouble())
            appendMetric(sb, "victron_last_seen_timestamp", labels, System.currentTimeMillis() / 1000.0)

            if (data.containsKey("charge_state")) {
                val stateStr = data["charge_state"] as? String
                val stateNum = when (stateStr) {
                    "BULK" -> 3
                    "ABSORPTION" -> 4
                    "FLOAT" -> 5
                    "OFF" -> 0
                    else -> -1
                }
                appendMetric(sb, "victron_charge_state", labels, stateNum.toDouble())
            }

            if (data.containsKey("charger_error")) {
                appendMetric(sb, "victron_charger_error", labels, 0.0) // numeric error or 0 if none
            }

            if (data.containsKey("soc_percent")) {
                appendMetric(sb, "victron_soc_percent", labels, data["soc_percent"] as? Number)
            }
        }

        return newFixedLengthResponse(Response.Status.OK, "text/plain; version=0.0.4; charset=utf-8", sb.toString())
    }

    private fun buildLabels(mac: String, device: ParsedDevice): String {
        val type = device.data["device_type"] as? String ?: "unknown"
        val model = com.lakshaysethi.victronbleexporter.parser.VictronParser.getModelName(device.modelId)
        return "{device=\"${model.replace("\"", "")}\",mac=\"$mac\",type=\"$type\"}"
    }

    private fun appendMetric(sb: StringBuilder, name: String, labels: String, value: Number?) {
        if (value != null) {
            sb.append("$name$labels ${value.toDouble()}\n")
        }
    }

    private fun serveDevicesJson(): Response {
        val all = MetricsStore.getAll()
        val json = buildString {
            append("[\n")
            all.values.forEachIndexed { index, dev ->
                if (index > 0) append(",\n")
                append("  {\"mac\":\"${dev.mac}\", \"modelId\":${dev.modelId}, \"recordType\":${dev.recordType}, \"rssi\":${dev.rssi}, \"data\":{")
                dev.data.forEach { (k, v) ->
                    append("\"$k\": ${if (v is String) "\"$v\"" else v}")
                    if (k != dev.data.keys.last()) append(",")
                }
                append("}}")
            }
            append("\n]")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Prometheus exporter started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start exporter", e)
        }
    }

    fun stopServer() {
        stop()
        Log.i(TAG, "Prometheus exporter stopped")
    }
}