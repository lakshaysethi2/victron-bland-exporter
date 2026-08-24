package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.charger.ChargerSchedule
import com.lakshaysethi.victronbleexporter.data.RemoteChargerStore
import com.lakshaysethi.victronbleexporter.parser.ParsedDevice
import com.lakshaysethi.victronbleexporter.parser.VictronParser
import java.security.MessageDigest

/**
 * HTTP surface for REMOTE charger control, served by [PrometheusExporter]
 * under `/charger*` and reachable from any browser through the Cloudflare
 * named tunnel (`https://mppt.lak.nz/charger`).
 *
 * Routes:
 *   GET  /charger        -> mobile control page (shell; every API call inside
 *                           it requires the secret — the page shows nothing and
 *                           does nothing without it)
 *   GET  /charger/status -> JSON status snapshot (charger + daily schedule + live Instant Readout + last charger debug lines)
 *   POST /charger        -> JSON body {"action":"on"|"off"} flips the charger
 *   POST /charger/schedule -> JSON {enabled, enable, disable} saves the daily window
 *   GET  /voltage        -> JSON voltage settings (auth required)
 *   POST /voltage        -> JSON {battery_voltage_setting, absorption_voltage, float_voltage}
 *                           writes the matching GATT registers (auth required; confirm in UI)
 *
 * Auth: the secret set in the app's Remote Control settings must be sent as an
 * `X-Remote-Secret: <secret>` header (or `Authorization: Bearer <secret>`).
 * Compared constant-time; never logged, never in the URL. When the feature is
 * disabled or no secret is configured, every /charger* route answers 404 so the
 * surface is invisible to port scanners.
 *
 * The command path is a seam: the production [ChargerCommandSender] forwards a
 * CHARGER_SET intent to the foreground service (which runs the real
 * ChargerController.setMode over BLE); tests inject a fake sender. Keep this
 * class free of Android framework calls so the routing/auth/JSON logic is
 * unit-testable on the JVM.
 */
class RemoteChargerHttp(
    private val settingsProvider: () -> RemoteChargerStore.RemoteChargerSettings,
    private val statusProvider: () -> ChargerStatusSnapshot,
    private val macProvider: () -> String?,
    private val commandSender: ChargerCommandSender,
    private val scheduleSender: ScheduleCommandSender? = null,
    private val voltageCommandSender: VoltageCommandSender? = null,
) {

    /**
     * Route one request. [method] is the uppercase HTTP method name, [headers]
     * are NanoHTTPD's lowercased header names, [body] is the raw POST body
     * (empty for GET). Pure logic — returns an [HttpResult] the server renders.
     */
    fun handle(uri: String, method: String, headers: Map<String, String>, body: String): HttpResult {
        if (!uri.startsWith("/charger") && uri != "/voltage") {
            return notFound()
        }
        val settings = settingsProvider()
        if (!settings.enabled || settings.authSecret.isBlank()) {
            return notFound()
        }
        // The control page is a static shell (login form + JS); a browser cannot
        // attach custom headers to a top-level navigation, so the page itself is
        // served without the secret. Everything functional on the page — status
        // and commands — still requires the secret below.
        if (uri == "/charger" && method == "GET") {
            return HttpResult(200, MIME_HTML, CONTROL_PAGE)
        }
        // Browser navigation cannot send X-Remote-Secret; serve the inert voltage shell the same way as /charger.
        val wantsHtml = (headers["accept"] ?: "").contains("text/html", ignoreCase = true)
        if (uri == "/voltage" && method == "GET" && wantsHtml && RemoteChargerAuth.extractSecret(headers) == null) {
            return HttpResult(200, MIME_HTML, VOLTAGE_PAGE)
        }
        val secret = RemoteChargerAuth.extractSecret(headers)
        if (!RemoteChargerAuth.constantTimeEquals(settings.authSecret, secret)) {
            return HttpResult(
                statusCode = 401,
                mimeType = MIME_JSON,
                body = "{\"error\":\"unauthorized\"}\n",
                headers = mapOf("WWW-Authenticate" to "Bearer"),
            )
        }
        return when {
            uri == "/charger/status" && method == "GET" ->
                HttpResult(200, MIME_JSON, statusProvider().toJson() + "\n")
            uri == "/charger" && method == "POST" -> handleCommand(body)
            uri == "/charger/schedule" && method == "POST" -> handleSchedule(body)
            uri == "/voltage" && method == "GET" -> handleVoltageGet()
            uri == "/voltage" && method == "POST" -> handleVoltagePost(body)
            else -> notFound()
        }
    }

    private fun handleCommand(body: String): HttpResult {
        val action = parseAction(body)
        if (action == null) {
            return HttpResult(
                statusCode = 400,
                mimeType = MIME_JSON,
                body = "{\"error\":\"body must be JSON: {\\\"action\\\": \\\"on\\\" | \\\"off\\\"}\"}\n",
            )
        }
        val mac = macProvider()
        if (mac.isNullOrBlank()) {
            return HttpResult(
                statusCode = 503,
                mimeType = MIME_JSON,
                body = "{\"error\":\"no charger configured — set a charger device MAC in the app first\"}\n",
            )
        }
        val enable = action == "on"
        commandSender.sendChargerCommand(enable, mac)
        return HttpResult(
            statusCode = 202,
            mimeType = MIME_JSON,
            body = "{\"accepted\":true,\"action\":\"${if (enable) "on" else "off"}\",\"mac\":\"${RemoteChargerHttpJson.escape(mac)}\"}\n",
        )
    }

    private fun handleSchedule(body: String): HttpResult {
        val parsed = parseSchedule(body)
        if (parsed == null) {
            return HttpResult(
                statusCode = 400,
                mimeType = MIME_JSON,
                body = "{\"error\":\"body must be JSON: {\\\"enabled\\\":true,\\\"enable\\\":\\\"HH:mm\\\",\\\"disable\\\":\\\"HH:mm\\\"}\"}\n",
            )
        }
        val sender = scheduleSender
            ?: return HttpResult(503, MIME_JSON, "{\"error\":\"schedule control unavailable on this build\"}\n")
        val mac = macProvider()
        if (mac.isNullOrBlank()) {
            return HttpResult(
                statusCode = 503,
                mimeType = MIME_JSON,
                body = "{\"error\":\"no charger configured — set a charger device MAC in the app first\"}\n",
            )
        }
        sender.saveSchedule(parsed.enabled, parsed.enable, parsed.disable, mac)
        return HttpResult(
            statusCode = 202,
            mimeType = MIME_JSON,
            body = "{\"accepted\":true,\"enabled\":${parsed.enabled},\"enable\":\"${RemoteChargerHttpJson.escape(parsed.enable)}\",\"disable\":\"${RemoteChargerHttpJson.escape(parsed.disable)}\"}\n",
        )
    }

    private data class ScheduleBody(val enabled: Boolean, val enable: String, val disable: String)

    private fun parseSchedule(body: String): ScheduleBody? {
        val trimmed = body.trim()
        val enabled = ENABLED_REGEX.find(trimmed)?.groupValues?.get(1)?.lowercase() ?: return null
        val enable = ENABLE_TIME_REGEX.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
        val disable = DISABLE_TIME_REGEX.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
        if (!ChargerSchedule.isValidTime(enable) || !ChargerSchedule.isValidTime(disable)) return null
        return ScheduleBody(enabled == "true", enable, disable)
    }

    private fun handleVoltageGet(): HttpResult {
        if (AppState.voltageSettings == null) {
            macProvider()?.takeIf { it.isNotBlank() }?.let { voltageCommandSender?.requestVoltageRead(it) }
        }
        val vs = AppState.voltageSettings
        val body = buildString {
            append("{")
            append("\"battery_voltage_setting\":").append(vs?.batteryVoltageSetting?.toString() ?: "null")
            append(",\"absorption_voltage\":").append(vs?.absorptionVolts?.toString() ?: "null")
            append(",\"float_voltage\":").append(vs?.floatVolts?.toString() ?: "null")
            append(",\"equalisation_voltage\":").append(vs?.equalisationVolts?.toString() ?: "null")
            append(",\"charger_voltage\":").append(vs?.chargerVolts?.toString() ?: "null")
            append(",\"panel_voltage\":").append(vs?.panelVolts?.toString() ?: "null")
            append(",\"mac\":").append(if (AppState.chargerMac.isNullOrBlank()) "null" else "\"${RemoteChargerHttpJson.escape(AppState.chargerMac!!)}\"")
            append(",\"updatedAt\":").append(AppState.voltageSettingsUpdatedAt)
            append(",\"lastError\":").append(if (AppState.voltageSettingsLastError == null) "null" else "\"${RemoteChargerHttpJson.escape(AppState.voltageSettingsLastError!!)}\"")
            append(",\"busy\":").append(AppState.chargerBusy)
            append("}")
        }
        return HttpResult(200, MIME_JSON, body + "\n")
    }

    private fun handleVoltagePost(body: String): HttpResult {
        val mac = macProvider()
        if (mac.isNullOrBlank()) {
            return HttpResult(503, MIME_JSON, "{\"error\":\"no charger configured — set a charger device MAC in the app first\"}\n")
        }
        val parsed = parseVoltageBody(body)
        if (parsed == null) {
            return HttpResult(
                400, MIME_JSON,
                "{\"error\":\"body must be JSON with at least one of battery_voltage_setting (12/24/48), absorption_voltage, float_voltage\"}\n",
            )
        }
        val sender = voltageCommandSender
            ?: return HttpResult(503, MIME_JSON, "{\"error\":\"voltage control unavailable on this build\"}\n")
        val (battSetting, absVolts, floatVolts) = parsed
        if (battSetting != null) sender.sendBatteryVoltageSetting(mac, battSetting)
        if (absVolts != null || floatVolts != null) sender.sendChargingVoltages(mac, absVolts, floatVolts)
        return HttpResult(202, MIME_JSON, "{\"accepted\":true,\"mac\":\"${RemoteChargerHttpJson.escape(mac)}\"}\n")
    }

    private fun parseVoltageBody(body: String): Triple<Int?, Double?, Double?>? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val battMatch = Regex("\"battery_voltage_setting\"\\s*:\\s*(\\d+)").find(trimmed)
        val absMatch = Regex("\"absorption_voltage\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(trimmed)
        val floatMatch = Regex("\"float_voltage\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(trimmed)
        val batt = battMatch?.groupValues?.get(1)?.toIntOrNull()
        val abs = absMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val fl = floatMatch?.groupValues?.get(1)?.toDoubleOrNull()
        if (batt == null && abs == null && fl == null) return null
        if (batt != null && batt !in 0..48) return null
        if (abs != null && (abs < 0 || abs > 80)) return null
        if (fl != null && (fl < 0 || fl > 80)) return null
        return Triple(batt, abs, fl)
    }

    private fun notFound() = HttpResult(404, MIME_PLAINTEXT, "Not Found\n")

    private companion object {
        const val MIME_HTML = "text/html; charset=utf-8"
        const val MIME_JSON = "application/json; charset=utf-8"
        const val MIME_PLAINTEXT = "text/plain; charset=utf-8"

        val ACTION_REGEX = Regex("""(?s)"action"\s*:\s*"(on|off)"""", RegexOption.IGNORE_CASE)
        val ENABLED_REGEX = Regex("\"enabled\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        val ENABLE_TIME_REGEX = Regex("\"enable\"\\s*:\\s*\"([^\"]+)\"")
        val DISABLE_TIME_REGEX = Regex("\"disable\"\\s*:\\s*\"([^\"]+)\"")

        /** Tiny JSON field extractor — the API accepts exactly `{"action":"on"|"off"}`. */
        fun parseAction(body: String): String? =
            ACTION_REGEX.find(body.trim())?.groupValues?.get(1)?.lowercase()
    }
}

/** Auth helpers — pure, so unit tests exercise them on the JVM. */
internal object RemoteChargerAuth {

    /**
     * Constant-time comparison: MessageDigest.isEqual does not short-circuit on
     * the first mismatching byte, so timing does not leak where the secrets diverge.
     */
    fun constantTimeEquals(expected: String?, actual: String?): Boolean {
        if (expected == null || actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    /** Read the secret from `X-Remote-Secret` or `Authorization: Bearer <secret>`. */
    fun extractSecret(headers: Map<String, String>): String? {
        headers["x-remote-secret"]?.takeIf { it.isNotBlank() }?.let { return it }
        val auth = headers["authorization"] ?: return null
        val prefix = "Bearer "
        return if (auth.length > prefix.length &&
            auth.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
        ) {
            auth.substring(prefix.length).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}

/** Sends a charger flip command. Production: CHARGER_SET intent to the service. */
fun interface ChargerCommandSender {
    fun sendChargerCommand(enable: Boolean, mac: String)
}

/** Saves the daily enable/disable window. Production: CHARGER_SCHEDULE_SAVE intent. */
fun interface ScheduleCommandSender {
    fun saveSchedule(enabled: Boolean, enableTime: String, disableTime: String, mac: String)
}

/** Voltage settings commands (battery system voltage + charge voltages). */
interface VoltageCommandSender {
    fun sendBatteryVoltageSetting(mac: String, volts: Int)
    fun sendChargingVoltages(mac: String, absorptionVolts: Double?, floatVolts: Double?)
    fun requestVoltageRead(mac: String)
}

/** Fresh Instant Readout row for the remote page. */
data class LiveReadout(
    val mac: String,
    val model: String,
    val solarPowerW: Number?,
    val batteryVoltage: Number?,
    val batteryCurrent: Number?,
    val socPercent: Number?,
    val lastSeen: Long,
) {
    fun toJson(): String = buildString {
        append("{\"mac\":\"${RemoteChargerHttpJson.escape(mac)}\"")
        append(",\"model\":\"${RemoteChargerHttpJson.escape(model)}\"")
        append(",\"solarPowerW\":").append(solarPowerW ?: "null")
        append(",\"batteryVoltage\":").append(batteryVoltage ?: "null")
        append(",\"batteryCurrent\":").append(batteryCurrent ?: "null")
        append(",\"socPercent\":").append(socPercent ?: "null")
        append(",\"lastSeen\":").append(lastSeen)
        append("}")
    }

    companion object {
        fun fromDevice(device: ParsedDevice): LiveReadout = LiveReadout(
            mac = device.mac,
            model = VictronParser.getModelName(device.modelId),
            solarPowerW = device.data["solar_power_w"] as? Number,
            batteryVoltage = device.data["battery_voltage"] as? Number,
            batteryCurrent = device.data["battery_current"] as? Number,
            socPercent = device.data["soc_percent"] as? Number,
            lastSeen = device.lastSeen,
        )

        fun fromFreshMetrics(now: Long = System.currentTimeMillis()): List<LiveReadout> =
            MetricsStore.getFresh(now).values.sortedBy { it.mac }.map { fromDevice(it) }
    }
}

/** Immutable status snapshot for the web UI. Reuses the app's charger state. */
data class ChargerStatusSnapshot(
    val mode: Int?,
    val mac: String?,
    val busy: Boolean,
    val lastAction: String,
    val lastError: String?,
    val overrideUntil: Long,
    val stateUpdatedAt: Long,
    val scheduleEnabled: Boolean = false,
    val enableTime: String = ChargerSchedule.DEFAULT_ENABLE,
    val disableTime: String = ChargerSchedule.DEFAULT_DISABLE,
    val live: List<LiveReadout> = emptyList(),
    val debug: List<String> = emptyList(),
) {
    val modeText: String get() = ChargerProtocol.chargerModeText(mode)

    fun toJson(): String = buildString {
        append("{\"mode\":").append(if (mode == null) "null" else "\"${RemoteChargerHttpJson.escape(modeText)}\"")
        append(",\"modeCode\":").append(mode ?: "null")
        append(",\"mac\":").append(if (mac.isNullOrBlank()) "null" else "\"${RemoteChargerHttpJson.escape(mac!!)}\"")
        append(",\"busy\":").append(busy)
        append(",\"lastAction\":\"${RemoteChargerHttpJson.escape(lastAction)}\"")
        append(",\"lastError\":").append(if (lastError == null) "null" else "\"${RemoteChargerHttpJson.escape(lastError)}\"")
        append(",\"overrideUntil\":").append(overrideUntil)
        append(",\"stateUpdatedAt\":").append(stateUpdatedAt)
        append(",\"scheduleEnabled\":").append(scheduleEnabled)
        append(",\"enableTime\":\"${RemoteChargerHttpJson.escape(enableTime)}\"")
        append(",\"disableTime\":\"${RemoteChargerHttpJson.escape(disableTime)}\"")
        append(",\"live\":[")
        live.forEachIndexed { i, row ->
            if (i > 0) append(",")
            append(row.toJson())
        }
        append("],\"debug\":[")
        debug.forEachIndexed { i, line ->
            if (i > 0) append(",")
            append("\"${RemoteChargerHttpJson.escape(line)}\"")
        }
        append("]}")
    }

    companion object {
        const val REMOTE_DEBUG_LINES = 20

        fun fromAppState(): ChargerStatusSnapshot = ChargerStatusSnapshot(
            mode = AppState.chargerMode,
            mac = AppState.chargerMac,
            busy = AppState.chargerBusy,
            lastAction = AppState.chargerLastAction,
            lastError = AppState.chargerLastError,
            overrideUntil = AppState.chargerOverrideUntil,
            stateUpdatedAt = AppState.chargerStateUpdatedAt,
        )
    }
}

/** JSON string escaping shared by the handler and the status snapshot. */
internal object RemoteChargerHttpJson {
    fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}

/** Plain result object the server converts into a NanoHTTPD response. */
data class HttpResult(
    val statusCode: Int,
    val mimeType: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Static control page served at GET /charger (shell only — see class doc). */
private val VOLTAGE_PAGE: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MPPT Voltage Settings</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, sans-serif; background: #0b1220; color: #e6edf7; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px; }
  .card { width: 100%; max-width: 420px; background: #111c30; border: 1px solid #24344d; border-radius: 16px; padding: 20px; }
  h1 { margin: 0 0 4px; font-size: 22px; }
  .sub { color: #8fa3bf; font-size: 13px; margin-bottom: 12px; }
  .row { display: flex; gap: 8px; margin-bottom: 10px; }
  input { flex: 1; padding: 14px; border-radius: 12px; border: 1px solid #24344d; background: #0d1626; color: #e6edf7; font-size: 16px; }
  button { padding: 14px; border-radius: 12px; border: none; font-weight: 700; cursor: pointer; }
  button.primary { background: #22c55e; color: #0b1220; flex: 1; }
  button.secondary { background: #24344d; color: #e6edf7; }
  .err { color: #f87171; font-size: 13px; min-height: 18px; margin: 6px 0; }
  .kv { font-size: 13px; color: #8fa3bf; line-height: 1.6; }
  .warn { font-size: 12px; color: #fbbf24; margin-top: 8px; }
</style>
</head>
<body>
<div class="card">
  <h1>Voltage Settings</h1>
  <div class="sub">Battery system voltage + absorption / float — writes to the MPPT over BLE</div>
  <div class="kv" id="kv">Loading…</div>
  <div class="err" id="err"></div>
  <input type="password" id="secret" placeholder="Remote secret" autocomplete="off" style="width:100%;margin-bottom:8px;">
  <div class="row"><button class="secondary" id="btnUnlock" style="flex:1">Unlock</button><button class="secondary" id="btnRefresh">Refresh</button></div>
  <div class="row"><input id="batt" placeholder="System V (e.g. 24)"> <button class="primary" id="btnBatt">Set Battery V</button></div>
  <div class="row"><input id="abs" placeholder="Absorption V"> <input id="flt" placeholder="Float V"> <button class="primary" id="btnCharge">Set</button></div>
  <div class="warn">Each Set asks for confirmation in the app. Over-the-air changes affect charging — only set values you intend.</div>
</div>
<script>
(function(){
  var KEY="mppt_remote_secret"; var secret=null; try{secret=sessionStorage.getItem(KEY);}catch(e){}
  var kv=document.getElementById("kv"), err=document.getElementById("err"), secretInput=document.getElementById("secret");
  function setErr(t){err.textContent=t||"";}
  function api(path, opts){opts=opts||{}; opts.headers=Object.assign({"X-Remote-Secret":secret}, opts.headers||{}); return fetch(path, opts).then(function(r){ if(r.status===401){ secret=null; try{sessionStorage.removeItem(KEY);}catch(e){} setErr("Wrong secret — enter it again."); } return r; }); }
  function load(){ if(!secret){ kv.textContent="Enter the remote secret to unlock."; return; } setErr(""); api("/voltage").then(function(r){return r.json().then(function(d){return {r:r,d:d};});}).then(function(x){ if(x.r.status===401) return; if(x.r.ok){ var d=x.d; kv.textContent="Battery: "+(d.battery_voltage_setting!=null?d.battery_voltage_setting+" V":"—")+"  •  Abs: "+(d.absorption_voltage!=null?d.absorption_voltage+" V":"—")+"  •  Float: "+(d.float_voltage!=null?d.float_voltage+" V":"—")+(d.charger_voltage!=null?"  •  Live "+d.charger_voltage+" V":"")+(d.panel_voltage!=null?"  •  Panel "+d.panel_voltage+" V":""); } else setErr(x.d.error||("Status "+x.r.status)); }).catch(function(){ setErr("Can't reach the app."); }); }
  function post(body){ if(!secret) return; setErr(""); api("/voltage",{method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)}).then(function(r){return r.json().then(function(d){return {r:r,d:d};});}).then(function(x){ if(x.r.status===401) return; if(!x.r.ok) setErr(x.d.error||("Status "+x.r.status)); else setTimeout(load, 1500); }).catch(function(){ setErr("Request failed."); }); }
  document.getElementById("btnUnlock").addEventListener("click", function(){ var v=secretInput.value.trim(); if(!v) return; secret=v; try{sessionStorage.setItem(KEY,v);}catch(e){} secretInput.value=""; load(); });
  secretInput.addEventListener("keydown", function(ev){ if(ev.key==="Enter") document.getElementById("btnUnlock").click(); });
  document.getElementById("btnRefresh").addEventListener("click", load);
  document.getElementById("btnBatt").addEventListener("click", function(){ var n=parseInt(document.getElementById("batt").value,10); if(isNaN(n)) return setErr("Enter 0–48 V"); if(!confirm("Set battery system voltage to "+n+" V? This changes charging.")) return; post({battery_voltage_setting:n}); });
  document.getElementById("btnCharge").addEventListener("click", function(){ var a=document.getElementById("abs").value.trim(), f=document.getElementById("flt").value.trim(); var body={}; if(a) body.absorption_voltage=parseFloat(a); if(f) body.float_voltage=parseFloat(f); if(!body.absorption_voltage && !body.float_voltage) return setErr("Enter absorption and/or float"); if(!confirm("Set "+JSON.stringify(body)+"?")) return; post(body); });
  load(); setInterval(load, 4000);
})();
</script>
</body>
</html>
""".trimIndent()

private val CONTROL_PAGE: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MPPT Charger Control</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; background: #0b1220; color: #e6edf7; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px; }
  .card { width: 100%; max-width: 420px; background: #111c30; border: 1px solid #24344d; border-radius: 16px; padding: 20px; }
  h1 { margin: 0 0 4px; font-size: 22px; }
  .sub { color: #8fa3bf; font-size: 13px; margin-bottom: 18px; }
  .status { display: flex; align-items: center; gap: 12px; background: #0d1626; border: 1px solid #24344d; border-radius: 12px; padding: 14px; margin-bottom: 14px; }
  .dot { width: 12px; height: 12px; border-radius: 50%; background: #64748b; flex: none; }
  .dot.on { background: #22c55e; box-shadow: 0 0 8px #22c55e88; }
  .dot.off { background: #ef4444; box-shadow: 0 0 8px #ef444488; }
  .dot.busy { background: #f59e0b; animation: pulse 1s infinite; }
  .status .txt { flex: 1; }
  .status .label { font-size: 12px; color: #8fa3bf; }
  .status .value { font-size: 20px; font-weight: 700; }
  .status .meta { font-size: 12px; color: #8fa3bf; margin-top: 3px; line-height: 1.4; }
  #dbg { margin: 0; max-height: 160px; overflow: auto; white-space: pre-wrap; word-break: break-word; font: 11px/1.4 ui-monospace, monospace; color: #8fa3bf; }
  .btn { display: block; width: 100%; border: none; border-radius: 12px; padding: 16px; font-size: 17px; font-weight: 700; color: #0b1220; margin-bottom: 10px; cursor: pointer; }
  .btn:disabled { opacity: .45; }
  .btn.on { background: #22c55e; }
  .btn.off { background: #ef4444; }
  .btn.small { background: #24344d; color: #e6edf7; font-weight: 600; padding: 12px; font-size: 15px; }
  input[type=password], input[type=text] { width: 100%; padding: 14px; border-radius: 12px; border: 1px solid #24344d; background: #0d1626; color: #e6edf7; font-size: 16px; margin-bottom: 10px; }
  .row { display: flex; gap: 8px; }
  .err { color: #f87171; font-size: 13px; margin: 8px 0; min-height: 18px; }
  .hint { color: #8fa3bf; font-size: 12px; line-height: 1.5; }
  @keyframes pulse { 50% { opacity: .4; } }
</style>
</head>
<body>
<div class="card">
  <h1>MPPT Charger</h1>
  <div class="sub">Remote enable / disable</div>
  <div class="status">
    <div class="dot" id="dot"></div>
    <div class="txt">
      <div class="label">Charger</div>
      <div class="value" id="state">&mdash;</div>
      <div class="meta" id="meta"></div>
    </div>
  </div>
  <div class="status" id="liveBox" style="display:block">
    <div class="txt">
      <div class="label">Live Instant Readout</div>
      <div class="meta" id="live">No fresh advertisement</div>
    </div>
  </div>
  <div class="status">
    <div class="txt">
      <div class="label">Charger log</div>
      <pre id="dbg">No charger log yet</pre>
    </div>
  </div>
  <div class="err" id="err"></div>
  <input type="password" id="secret" placeholder="Remote secret" autocomplete="off" autocapitalize="off" spellcheck="false">
  <button class="btn small" id="btnUnlock">Unlock</button>
  <button class="btn on" id="btnOn" disabled>ENABLE CHARGER</button>
  <button class="btn off" id="btnOff" disabled>DISABLE CHARGER</button>
  <div class="sub" style="margin:14px 0 8px">Daily schedule (phone time)</div>
  <label class="hint"><input type="checkbox" id="schedOn"> Enforce window</label>
  <div class="row"><input type="text" id="enTime" placeholder="ON 08:30" inputmode="numeric"><input type="text" id="disTime" placeholder="OFF 18:00" inputmode="numeric"></div>
  <button class="btn small" id="btnSched" disabled>Save schedule</button>
  <button class="btn small" id="btnRefresh">Refresh</button>
  <div class="hint"><a href="/voltage" style="color:#8fa3bf">Voltage settings</a></div>
  <div class="hint">The secret is stored only in this browser session and sent only in a request header &mdash; never in the URL.</div>
</div>
<script>
(function () {
  var KEY = "mppt_remote_secret";
  var secret = null;
  try { secret = sessionStorage.getItem(KEY); } catch (e) {}
  var dot = document.getElementById("dot"), state = document.getElementById("state"),
      meta = document.getElementById("meta"), err = document.getElementById("err"),
      btnOn = document.getElementById("btnOn"), btnOff = document.getElementById("btnOff"),
      btnRefresh = document.getElementById("btnRefresh"),
      btnUnlock = document.getElementById("btnUnlock"),
      btnSched = document.getElementById("btnSched"),
      schedOn = document.getElementById("schedOn"),
      enTime = document.getElementById("enTime"),
      disTime = document.getElementById("disTime"),
      secretInput = document.getElementById("secret");
  function setErr(t) { err.textContent = t || ""; }
  function setBusy(b) { btnOn.disabled = b; btnOff.disabled = b; btnSched.disabled = b; if (b) { dot.className = "dot busy"; } }
  function api(path, opts) {
    opts = opts || {};
    opts.headers = Object.assign({ "X-Remote-Secret": secret }, opts.headers || {});
    return fetch(path, opts).then(function (r) {
      if (r.status === 401) {
        secret = null;
        try { sessionStorage.removeItem(KEY); } catch (e) {}
        setErr("Wrong secret &mdash; enter it again.");
        setBusy(true);
      }
      return r;
    });
  }
  function renderStatus(data) {
    if (data.mode === "ON") { dot.className = "dot on"; state.textContent = "ON"; }
    else if (data.mode === "OFF") { dot.className = "dot off"; state.textContent = "OFF"; }
    else { dot.className = "dot"; state.textContent = "Unknown"; }
    var parts = [];
    if (data.busy) parts.push("working\u2026");
    if (data.lastAction) parts.push(data.lastAction);
    if (data.lastError) parts.push(data.lastError);
    if (data.scheduleEnabled) parts.push("schedule " + (data.enableTime || "?") + "-" + (data.disableTime || "?"));
    meta.textContent = parts.join(" \u00b7 ");
    if (typeof data.scheduleEnabled === "boolean") schedOn.checked = data.scheduleEnabled;
    if (data.enableTime) enTime.value = data.enableTime;
    if (data.disableTime) disTime.value = data.disableTime;
    var live = document.getElementById("live");
    var devices = data.live || [];
    if (!devices.length) { live.textContent = "No fresh advertisement"; }
    else {
      live.innerHTML = devices.map(function (d) {
        var bits = [];
        if (d.solarPowerW != null) bits.push(d.solarPowerW + " W");
        if (d.batteryVoltage != null) bits.push(d.batteryVoltage + " V");
        if (d.batteryCurrent != null) bits.push(d.batteryCurrent + " A");
        if (d.socPercent != null) bits.push(d.socPercent + "%");
        return "<div><b>" + (d.model || d.mac) + "</b> " + (bits.join(" \u00b7 ") || d.mac) + "</div>";
      }).join("");
    }
    var dbg = document.getElementById("dbg");
    var lines = data.debug || [];
    dbg.textContent = lines.length ? lines.join("\n") : "No charger log yet";
    setBusy(!!data.busy);
  }
  function loadStatus() {
    if (!secret) { setBusy(true); setErr("Enter the remote secret to unlock."); return; }
    setErr("");
    api("/charger/status").then(function (r) { return r.json().then(function (d) { return { r: r, d: d }; }); })
      .then(function (x) {
        if (x.r.status === 401) return;
        if (x.r.ok) renderStatus(x.d);
        else setErr(x.d.error || ("Status " + x.r.status));
      })
      .catch(function () { setErr("Can't reach the app (is the tunnel up?)."); });
  }
  function send(action) {
    if (!secret) return;
    setBusy(true); setErr("");
    api("/charger", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: action }) })
      .then(function (r) { return r.json().then(function (d) { return { r: r, d: d }; }); })
      .then(function (x) {
        if (x.r.status === 401) { setBusy(false); return; }
        if (x.r.ok) { if (x.d.error) setErr(x.d.error); loadStatus(); }
        else { setBusy(false); setErr(x.d.error || ("Status " + x.r.status)); }
      })
      .catch(function () { setBusy(false); setErr("Request failed."); });
  }
  function unlock() {
    var v = secretInput.value.trim();
    if (!v) return;
    secret = v;
    try { sessionStorage.setItem(KEY, v); } catch (e) {}
    secretInput.value = "";
    loadStatus();
  }
  btnUnlock.addEventListener("click", unlock);
  secretInput.addEventListener("keydown", function (ev) { if (ev.key === "Enter") unlock(); });
  function saveSched() {
    if (!secret) return;
    setBusy(true); setErr("");
    api("/charger/schedule", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: schedOn.checked, enable: enTime.value.trim(), disable: disTime.value.trim() }) })
      .then(function (r) { return r.json().then(function (d) { return { r: r, d: d }; }); })
      .then(function (x) {
        if (x.r.status === 401) { setBusy(false); return; }
        if (x.r.ok) { if (x.d.error) setErr(x.d.error); loadStatus(); }
        else { setBusy(false); setErr(x.d.error || ("Status " + x.r.status)); }
      })
      .catch(function () { setBusy(false); setErr("Request failed."); });
  }
  btnOn.addEventListener("click", function () { send("on"); });
  btnOff.addEventListener("click", function () { send("off"); });
  btnSched.addEventListener("click", saveSched);
  btnRefresh.addEventListener("click", loadStatus);
  loadStatus();
  setInterval(loadStatus, 3000);
})();
</script>
</body>
</html>
""".trimIndent()
