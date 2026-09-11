"""Remote charger page for `mppt_ble serve` (https://mppt.lak.nz/charger)."""

from __future__ import annotations


def render_page(host: str) -> str:
    host = "".join(c for c in host if c.isalnum() or c in ".-") or "local"
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MPPT Charger</title>
<style>
  :root {{ color-scheme: dark; }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; font: 16px/1.4 system-ui, sans-serif; background: #0b1220; color: #e6edf7; }}
  .card {{ max-width: 420px; margin: 0 auto; padding: 20px 16px 40px; }}
  h1 {{ font-size: 22px; margin: 0 0 4px; }}
  .sub {{ color: #8fa3bf; font-size: 13px; margin-bottom: 16px; }}
  .status {{ display: flex; gap: 12px; align-items: flex-start; background: #121b2d; border-radius: 14px; padding: 14px; margin-bottom: 10px; }}
  .dot {{ width: 14px; height: 14px; border-radius: 50%; background: #4b5d78; margin-top: 6px; flex: none; }}
  .dot.on {{ background: #22c55e; }}
  .dot.off {{ background: #ef4444; }}
  .dot.busy {{ background: #eab308; animation: pulse 1s infinite; }}
  .label {{ font-size: 11px; letter-spacing: .04em; text-transform: uppercase; color: #8fa3bf; }}
  .value {{ font-size: 22px; font-weight: 700; }}
  .meta {{ font-size: 12px; color: #8fa3bf; margin-top: 4px; }}
  .grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 12px; }}
  .tile {{ background: #121b2d; border-radius: 12px; padding: 12px; }}
  .tile .n {{ font-size: 20px; font-weight: 700; }}
  .tile.warn {{ outline: 1px solid #eab308; }}
  .btn {{ display: block; width: 100%; border: none; border-radius: 12px; padding: 16px; font-size: 17px; font-weight: 700; color: #0b1220; margin-bottom: 10px; cursor: pointer; }}
  .btn:disabled {{ opacity: .45; }}
  .btn.pulse {{ background: #38bdf8; }}
  .btn.on {{ background: #22c55e; }}
  .btn.off {{ background: #ef4444; }}
  .btn.small {{ background: #24344d; color: #e6edf7; font-weight: 600; padding: 12px; font-size: 15px; }}
  input[type=password] {{ width: 100%; padding: 14px; border-radius: 12px; border: 1px solid #24344d; background: #0d1626; color: #e6edf7; font-size: 16px; margin-bottom: 10px; }}
  .err {{ color: #f87171; font-size: 13px; margin: 8px 0; min-height: 18px; }}
  .hint {{ color: #8fa3bf; font-size: 12px; line-height: 1.5; }}
  @keyframes pulse {{ 50% {{ opacity: .4; }} }}
</style>
</head>
<body>
<div class="card">
  <h1>MPPT Charger</h1>
  <div class="sub">{host} · downstairs Linux · pulse restarts the cascade</div>
  <div class="status">
    <div class="dot" id="dot"></div>
    <div>
      <div class="label">Charger</div>
      <div class="value" id="state">&mdash;</div>
      <div class="meta" id="meta"></div>
    </div>
  </div>
  <div class="grid">
    <div class="tile" id="tPv"><div class="label">Panel</div><div class="n" id="nPv">&mdash;</div></div>
    <div class="tile" id="tOut"><div class="label">Victron out</div><div class="n" id="nOut">&mdash;</div></div>
    <div class="tile" id="tDv"><div class="label">Gap</div><div class="n" id="nDv">&mdash;</div></div>
    <div class="tile" id="tW"><div class="label">Solar</div><div class="n" id="nW">&mdash;</div></div>
  </div>
  <div class="status"><div><div class="label">Watchdog</div><div class="meta" id="wd">Unlock to see live numbers</div></div></div>
  <div class="err" id="err"></div>
  <input type="password" id="secret" placeholder="Remote secret" autocomplete="off" autocapitalize="off" spellcheck="false">
  <button class="btn small" id="btnUnlock">Unlock</button>
  <button class="btn pulse" id="btnPulse" disabled>PULSE (off then on)</button>
  <button class="btn on" id="btnOn" disabled>ENABLE</button>
  <button class="btn off" id="btnOff" disabled>DISABLE</button>
  <button class="btn small" id="btnRead" disabled>Read state</button>
  <button class="btn small" id="btnRefresh">Refresh</button>
  <div class="hint">Secret stays in this browser session and is sent only as X-Remote-Secret, never in the URL. Panel voltage is GATT 0xEDBB. Victron out is the bus into the next MPPT, not the cells.</div>
</div>
<script>
(function () {{
  var KEY = "mppt_remote_secret";
  var secret = null;
  try {{ secret = sessionStorage.getItem(KEY); }} catch (e) {{}}
  var dot = document.getElementById("dot"), state = document.getElementById("state"),
      meta = document.getElementById("meta"), err = document.getElementById("err"),
      wd = document.getElementById("wd"),
      btnOn = document.getElementById("btnOn"), btnOff = document.getElementById("btnOff"),
      btnPulse = document.getElementById("btnPulse"), btnRead = document.getElementById("btnRead"),
      btnUnlock = document.getElementById("btnUnlock"), secretInput = document.getElementById("secret");
  function setErr(t) {{ err.textContent = t || ""; }}
  function setBusy(b) {{
    btnOn.disabled = b; btnOff.disabled = b; btnPulse.disabled = b; btnRead.disabled = b;
    if (b) dot.className = "dot busy";
  }}
  function fmt(n, u) {{
    if (n == null || n === undefined) return "—";
    return (Math.round(n * 100) / 100) + u;
  }}
  function api(path, opts) {{
    opts = opts || {{}};
    opts.headers = Object.assign({{ "X-Remote-Secret": secret }}, opts.headers || {{}});
    return fetch(path, opts).then(function (r) {{
      if (r.status === 401) {{
        secret = null;
        try {{ sessionStorage.removeItem(KEY); }} catch (e) {{}}
        setErr("Wrong secret — enter it again.");
        setBusy(true);
      }}
      return r;
    }});
  }}
  function render(d) {{
    var mode = d.modeText || d.mode;
    if (mode === "ON" || mode === 1) {{ dot.className = "dot on"; state.textContent = "ON"; }}
    else if (mode === "OFF" || mode === 0 || mode === 4) {{ dot.className = "dot off"; state.textContent = "OFF"; }}
    else {{ dot.className = "dot"; state.textContent = "Unknown"; }}
    document.getElementById("nPv").textContent = fmt(d.panelVoltage, " V");
    document.getElementById("nOut").textContent = fmt(d.outputVoltage != null ? d.outputVoltage : d.batteryVoltage, " V");
    document.getElementById("nDv").textContent = fmt(d.deltaVoltage, " V");
    document.getElementById("nW").textContent = fmt(d.solarPowerW, " W");
    document.getElementById("tDv").className = "tile" + (d.pulseCandidate ? " warn" : "");
    document.getElementById("tPv").className = "tile" + (d.pulseCandidate ? " warn" : "");
    var bits = [];
    if (d.chargeState) bits.push(d.chargeState);
    if (d.mac) bits.push(d.mac);
    if (d.message) bits.push(d.message);
    meta.textContent = bits.join(" · ") || "linux";
    var w = [];
    if (d.pulseCandidate) w.push("would pulse (high panel, low watts)");
    else w.push("watching");
    if (d.cooldownRemainingS > 0) w.push("cooldown " + Math.ceil(d.cooldownRemainingS / 60) + "m");
    if (d.lastPulseReason) w.push(d.lastPulseReason);
    if (d.lastBleAdAt) {{
      var age = Math.max(0, Math.round((Date.now() - d.lastBleAdAt) / 1000));
      w.push(age < 90 ? ("BLE " + age + "s") : ("BLE quiet " + Math.round(age / 60) + "m"));
    }}
    wd.textContent = w.join(" · ");
    setBusy(!!d.busy);
  }}
  function load() {{
    if (!secret) {{ setBusy(true); setErr("Enter the remote secret to unlock."); return; }}
    setErr("");
    api("/charger/status").then(function (r) {{ return r.json().then(function (d) {{ return {{ r: r, d: d }}; }}); }})
      .then(function (x) {{
        if (x.r.status === 401) return;
        if (x.r.ok) render(x.d);
        else setErr(x.d.error || ("Status " + x.r.status));
      }})
      .catch(function () {{ setErr("Can't reach the laptop (is the tunnel up?)."); }});
  }}
  function cmd(action) {{
    if (!secret) return;
    setBusy(true);
    api("/charger", {{ method: "POST", headers: {{ "Content-Type": "application/json" }}, body: JSON.stringify({{ action: action }}) }})
      .then(function (r) {{ return r.json().then(function (d) {{ return {{ r: r, d: d }}; }}); }})
      .then(function (x) {{
        if (x.r.status === 401) return;
        if (!x.d.success) setErr(x.d.message || x.d.error || "failed");
        load();
      }})
      .catch(function () {{ setErr("Command failed."); setBusy(false); }});
  }}
  function unlock() {{
    secret = secretInput.value.trim();
    if (!secret) {{ setErr("Enter the secret."); return; }}
    try {{ sessionStorage.setItem(KEY, secret); }} catch (e) {{}}
    load();
  }}
  btnUnlock.onclick = unlock;
  secretInput.addEventListener("keydown", function (e) {{ if (e.key === "Enter") unlock(); }});
  btnPulse.onclick = function () {{ cmd("restart"); }};
  btnOn.onclick = function () {{ cmd("on"); }};
  btnOff.onclick = function () {{ cmd("off"); }};
  btnRead.onclick = function () {{ cmd("read"); }};
  document.getElementById("btnRefresh").onclick = load;
  if (secret) {{ secretInput.value = ""; load(); }}
  setInterval(function () {{ if (secret) load(); }}, 5000);
}})();
</script>
</body>
</html>
"""
