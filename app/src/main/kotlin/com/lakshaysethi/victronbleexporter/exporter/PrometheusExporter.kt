package com.lakshaysethi.victronbleexporter.exporter

import android.util.Log
import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

private const val TAG = "PrometheusExporter"
private const val DEFAULT_PORT = 5338

/** Devices with no broadcast for longer than this are treated as stale (data metrics omitted). */
private const val STALE_AFTER_MS = 120_000L

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
        val now = System.currentTimeMillis()

        val online = all.filter { (mac, _) -> now - MetricsStore.lastSeenMillis(mac) <= STALE_AFTER_MS }
        sb.append("# HELP victron_devices_total Number of Victron devices reporting fresh data\n")
        sb.append("# TYPE victron_devices_total gauge\n")
        sb.append("victron_devices_total ${online.size}\n\n")

        // Charger control state (1 = charger enabled, 0 = disabled, -1 = unknown)
        val chargerMode = AppState.chargerMode
        sb.append("# HELP victron_charger_enabled Whether the MPPT charger is enabled (1) or disabled (0)\n")
        sb.append("# TYPE victron_charger_enabled gauge\n")
        sb.append(
            "victron_charger_enabled${AppState.chargerMac?.let { "{device=\"$it\"}" } ?: ""} " +
                "${when (chargerMode) { ChargerProtocol.MODE_CHARGER_ON -> 1; ChargerProtocol.MODE_CHARGER_OFF, ChargerProtocol.MODE_CHARGER_OFF_LEGACY -> 0; else -> -1 }}\n\n"
        )

        // Solar panel voltage, read over the charger GATT service (register 0xEDBB) while the service runs.
        // -1 = unknown (never read or last read failed); the state metric carries the reason.
        val pvLabels = buildString {
            append("{")
            AppState.chargerMac?.let { append("device=\"$it\"") }
            AppState.panelVoltageLastError?.let {
                if (AppState.chargerMac != null) append(",")
                append("error=\"${it.replace("\"", "'")}\"")
            }
            append("}")
        }
        sb.append("# HELP victron_panel_voltage_volts Solar panel (PV) input voltage, read over the charger GATT service (-1 = unknown)\n")
        sb.append("# TYPE victron_panel_voltage_volts gauge\n")
        appendMetric(sb, "victron_panel_voltage_volts", pvLabels, AppState.panelVoltageVolts ?: -1.0)
        sb.append("# HELP victron_panel_voltage_state 0=ok, 1=no charger MAC configured, 2=last GATT read failed, 3=never read\n")
        sb.append("# TYPE victron_panel_voltage_state gauge\n")
        val pvState = when {
            AppState.panelVoltageVolts != null -> 0
            AppState.panelVoltageLastError != null -> 2
            AppState.chargerMac.isNullOrBlank() -> 1
            else -> 3
        }
        sb.append("victron_panel_voltage_state$pvLabels $pvState\n")
        sb.append("\n")

        for ((mac, device) in all) {
            val lastSeen = MetricsStore.lastSeenMillis(mac)
            val labels = buildLabels(mac, device)
            val data = device.data

            // Liveness is always emitted; data metrics are omitted once the device goes stale
            // so Prometheus/Grafana show no data instead of a frozen last value.
            appendMetric(sb, "victron_last_seen_timestamp", labels, lastSeen / 1000.0)
            if (now - lastSeen > STALE_AFTER_MS) continue

            appendMetric(sb, "victron_battery_voltage_volts", labels, data["battery_voltage"] as? Number)
            appendMetric(sb, "victron_battery_current_amps", labels, data["battery_current"] as? Number)
            appendMetric(sb, "victron_solar_power_watts", labels, data["solar_power_w"] as? Number)
            appendMetric(sb, "victron_yield_today_wh", labels, data["yield_today_wh"] as? Number)
            appendMetric(sb, "victron_load_current_amps", labels, data["load_current_a"] as? Number)

            // Common
            appendMetric(sb, "victron_rssi_dbm", labels, device.rssi.toDouble())

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