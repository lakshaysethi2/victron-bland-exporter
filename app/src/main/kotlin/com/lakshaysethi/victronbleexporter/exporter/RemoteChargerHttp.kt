package com.lakshaysethi.victronbleexporter.exporter

import com.lakshaysethi.victronbleexporter.AppState
import com.lakshaysethi.victronbleexporter.charger.ChargerProtocol
import com.lakshaysethi.victronbleexporter.data.RemoteChargerStore
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
 *   GET  /charger/status -> JSON status snapshot (reuses AppState.chargerMode)
 *   POST /charger        -> JSON body {"action":"on"|"off"} flips the charger
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
) {

    /**
     * Route one request. [method] is the uppercase HTTP method name, [headers]
     * are NanoHTTPD's lowercased header names, [body] is the raw POST body
     * (empty for GET). Pure logic — returns an [HttpResult] the server renders.
     */
    fun handle(uri: String, method: String, headers: Map<String, String>, body: String): HttpResult {
        if (!uri.startsWith("/charger")) {
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

    private fun notFound() = HttpResult(404, MIME_PLAINTEXT, "Not Found\n")

    private companion object {
        const val MIME_HTML = "text/html; charset=utf-8"
        const val MIME_JSON = "application/json; charset=utf-8"
        const val MIME_PLAINTEXT = "text/plain; charset=utf-8"

        val ACTION_REGEX = Regex("""(?s)"action"\s*:\s*"(on|off)"""", RegexOption.IGNORE_CASE)

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

/** Immutable status snapshot for the web UI. Reuses the app's charger state. */
data class ChargerStatusSnapshot(
    val mode: Int?,
    val mac: String?,
    val busy: Boolean,
    val lastAction: String,
    val lastError: String?,
    val overrideUntil: Long,
    val stateUpdatedAt: Long,
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
        append("}")
    }

    companion object {
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
  .btn { display: block; width: 100%; border: none; border-radius: 12px; padding: 16px; font-size: 17px; font-weight: 700; color: #0b1220; margin-bottom: 10px; cursor: pointer; }
  .btn:disabled { opacity: .45; }
  .btn.on { background: #22c55e; }
  .btn.off { background: #ef4444; }
  .btn.small { background: #24344d; color: #e6edf7; font-weight: 600; padding: 12px; font-size: 15px; }
  input[type=password] { width: 100%; padding: 14px; border-radius: 12px; border: 1px solid #24344d; background: #0d1626; color: #e6edf7; font-size: 16px; margin-bottom: 10px; }
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
  <div class="err" id="err"></div>
  <input type="password" id="secret" placeholder="Remote secret" autocomplete="off" autocapitalize="off" spellcheck="false">
  <button class="btn small" id="btnUnlock">Unlock</button>
  <button class="btn on" id="btnOn" disabled>ENABLE CHARGER</button>
  <button class="btn off" id="btnOff" disabled>DISABLE CHARGER</button>
  <button class="btn small" id="btnRefresh">Refresh</button>
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
      secretInput = document.getElementById("secret");
  function setErr(t) { err.textContent = t || ""; }
  function setBusy(b) { btnOn.disabled = b; btnOff.disabled = b; if (b) { dot.className = "dot busy"; } }
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
    meta.textContent = parts.join(" \u00b7 ");
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
  btnOn.addEventListener("click", function () { send("on"); });
  btnOff.addEventListener("click", function () { send("off"); });
  btnRefresh.addEventListener("click", loadStatus);
  loadStatus();
  setInterval(loadStatus, 3000);
})();
</script>
</body>
</html>
""".trimIndent()
