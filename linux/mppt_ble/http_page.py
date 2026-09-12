"""Remote charger page for `mppt_ble serve` (https://mppt.lak.nz/charger)."""

from __future__ import annotations


def _window_label(seconds: float) -> str:
    s = int(round(seconds))
    if s >= 3600 and s % 3600 == 0:
        return f"{s // 3600}h"
    if s >= 60 and s % 60 == 0:
        return f"{s // 60} min"
    if s >= 60:
        return f"{int(round(s / 60))} min"
    return f"{s}s"


def render_page(host: str, max_per_hour: int = 4, gap_avg_s: float = 120, extrema_s: float = 7200) -> str:
    host = "".join(c for c in host if c.isalnum() or c in ".-") or "local"
    n = max(1, int(max_per_hour))
    avg_w = _window_label(gap_avg_s)
    voc_w = _window_label(extrema_s)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b1220">
<title>MPPT</title>
<style>
  :root {{ color-scheme: dark; --bg:#070b14; --card:#121b2d; --muted:#8fa3bf; --text:#e6edf7; --line:#24344d; --ok:#22c55e; --bad:#ef4444; --go:#38bdf8; --wait:#eab308; }}
  * {{ box-sizing: border-box; }}
  html, body {{ margin: 0; background: var(--bg); color: var(--text); }}
  body {{ font: 16px/1.4 system-ui, sans-serif; padding: 16px 16px calc(24px + env(safe-area-inset-bottom)); }}
  .wrap {{ max-width: 440px; margin: 0 auto; }}
  h1 {{ font-size: 13px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); margin: 0 0 12px; }}
  .banner {{ border-radius: 16px; padding: 16px 16px 14px; margin-bottom: 12px; background: var(--card); }}
  .banner.ready {{ background: #1a2a14; outline: 1px solid #3f6d2a; }}
  .banner.cool {{ background: #0f1c2e; outline: 1px solid #1d4e78; }}
  .banner.work {{ background: #2a2410; outline: 1px solid #6b5a14; }}
  .kicker {{ font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }}
  .headline {{ font-size: 28px; font-weight: 750; letter-spacing: -.02em; margin: 4px 0 2px; font-variant-numeric: tabular-nums; }}
  .why {{ color: var(--muted); font-size: 13px; }}
  .grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 12px; }}
  .tile {{ background: var(--card); border-radius: 14px; padding: 14px 14px 12px; }}
  .tile.warn {{ outline: 1px solid var(--wait); }}
  .tile .n {{ font-size: 26px; font-weight: 750; font-variant-numeric: tabular-nums; letter-spacing: -.02em; }}
  .row {{ display: flex; gap: 8px; }}
  .btn {{ display: block; width: 100%; border: 0; border-radius: 14px; padding: 16px; font-size: 17px; font-weight: 700; cursor: pointer; margin-bottom: 8px; min-height: 48px; }}
  .btn:disabled {{ opacity: .4; }}
  .btn.pulse {{ background: var(--go); color: #04202b; }}
  .btn.on {{ background: var(--ok); color: #06210f; }}
  .btn.off {{ background: var(--bad); color: #2a0707; }}
  .btn.ghost {{ background: var(--line); color: var(--text); font-weight: 600; font-size: 15px; }}
  input[type=password] {{ width: 100%; padding: 14px; border-radius: 14px; border: 1px solid var(--line); background: #0d1626; color: var(--text); font-size: 16px; margin-bottom: 8px; }}
  .gate {{ margin-bottom: 12px; }}
  .err {{ color: #f87171; font-size: 13px; min-height: 18px; margin: 0 0 8px; }}
  .hist {{ list-style: none; margin: 0; padding: 0; }}
  .hist li {{ font-size: 12px; color: var(--muted); padding: 8px 0; border-bottom: 1px solid #1a2436; font-variant-numeric: tabular-nums; }}
  .hist li:last-child {{ border: 0; }}
  .ok {{ color: var(--ok); }}
  .fail {{ color: var(--bad); }}
  .hint {{ color: var(--muted); font-size: 12px; margin-top: 14px; line-height: 1.5; }}
  .tag {{ font-size: 9px; letter-spacing: .06em; text-transform: uppercase; padding: 2px 6px; border-radius: 6px; margin-left: 6px; vertical-align: 1px; }}
  .tag.live {{ background: #163024; color: #86efac; }}
  .tag.calc {{ background: #152a44; color: #7dd3fc; }}
  .tag.rule {{ background: #3a2a12; color: #fcd34d; }}
  .legend {{ display: flex; gap: 12px; flex-wrap: wrap; font-size: 11px; color: var(--muted); margin: 0 0 10px; align-items: center; }}
  .legend .tag {{ margin-left: 0; margin-right: 4px; }}
  .rules {{ background: var(--card); border-radius: 14px; padding: 4px 14px; margin-bottom: 12px; }}
  .rules div {{ font-size: 13px; color: var(--muted); padding: 8px 0; border-bottom: 1px solid #1a2436; line-height: 1.45; }}
  .rules div:last-child {{ border: 0; }}
  @media (prefers-reduced-motion: reduce) {{ * {{ animation: none !important; }} }}
</style>
</head>
<body>
<div class="wrap">
  <h1>{host}</h1>
  <div class="banner" id="banner">
    <div class="kicker" id="kicker">Locked</div>
    <div class="headline" id="headline">Unlock</div>
    <div class="why" id="why">Remote secret, then live panel voltage and pulse state.</div>
  </div>
  <div class="legend">
    <span><span class="tag live">live</span> measured</span>
    <span><span class="tag calc">calc</span> from samples</span>
    <span><span class="tag rule">hardcoded</span> config</span>
  </div>
  <div class="grid">
    <div class="tile" id="tPv"><div class="kicker">Panel <span class="tag live">live</span></div><div class="n" id="nPv">&mdash;</div></div>
    <div class="tile" id="tOut"><div class="kicker">Victron out <span class="tag live">live</span></div><div class="n" id="nOut">&mdash;</div></div>
    <div class="tile" id="tDv"><div class="kicker">Gap now <span class="tag calc">calc</span></div><div class="n" id="nDv">&mdash;</div></div>
    <div class="tile" id="tW"><div class="kicker">Solar <span class="tag live">live</span></div><div class="n" id="nW">&mdash;</div></div>
    <div class="tile" id="tAvg"><div class="kicker" id="kAvg">{avg_w} avg <span class="tag calc">calc</span></div><div class="n" id="nAvg">&mdash;</div></div>
    <div class="tile" id="tVoc"><div class="kicker" id="kVoc">{voc_w} Voc <span class="tag calc">calc</span></div><div class="n" id="nVoc">&mdash;</div></div>
  </div>
  <div class="kicker" style="margin:4px 0 6px">Auto-pulse rules</div>
  <div class="rules" id="rules"><div>Unlock to load live vs hardcoded values.</div></div>
  <div class="err" id="err"></div>
  <div class="gate" id="gate">
    <input type="password" id="secret" placeholder="Remote secret" autocomplete="off" autocapitalize="off" spellcheck="false">
    <button class="btn ghost" id="btnUnlock" type="button">Unlock</button>
  </div>
  <button class="btn pulse" id="btnPulse" type="button" disabled>Pulse cascade</button>
  <div class="row">
    <button class="btn on" id="btnOn" type="button" disabled>Enable</button>
    <button class="btn off" id="btnOff" type="button" disabled>Disable</button>
  </div>
  <button class="btn ghost" id="btnRead" type="button" disabled>Read charger</button>
  <div class="kicker" style="margin:16px 0 4px">Recent pulses</div>
  <ul class="hist" id="hist"><li>None yet this run</li></ul>
  <p class="hint">Panel is GATT 0xEDBB (live). Out is the bus into the next MPPT, not the cells. Gap now = panel − out (calc). {avg_w} avg and {voc_w} Voc are calculated from samples. Out band, rate limit, fractions, and cooldown are hardcoded in yield_config. Secret stays in this tab only. At most {n} auto-pulses per hour.</p>
</div>
<script>
(function () {{
  var KEY = "mppt_remote_secret";
  var secret = null, coolUntil = 0, last = null, ticking = false;
  try {{ secret = sessionStorage.getItem(KEY); }} catch (e) {{}}
  var banner = document.getElementById("banner");
  var kicker = document.getElementById("kicker");
  var headline = document.getElementById("headline");
  var why = document.getElementById("why");
  var err = document.getElementById("err");
  var gate = document.getElementById("gate");
  var secretInput = document.getElementById("secret");
  var btns = ["btnPulse","btnOn","btnOff","btnRead"].map(function (id) {{ return document.getElementById(id); }});
  function setErr(t) {{ err.textContent = t || ""; }}
  function setBusy(b) {{
    btns.forEach(function (el) {{ el.disabled = !secret || b; }});
  }}
  function fmt(n, d, u) {{
    if (n == null) return "—";
    return (Math.round(n * Math.pow(10, d)) / Math.pow(10, d)).toFixed(d) + u;
  }}
  function secsLabel(s) {{
    s = Number(s) || 0;
    if (s >= 3600 && s % 3600 === 0) return (s / 3600) + "h";
    if (s >= 60 && s % 60 === 0) return (s / 60) + " min";
    if (s >= 60) return Math.round(s / 60) + " min";
    return s + "s";
  }}
  function mmss(s) {{
    s = Math.max(0, Math.floor(s));
    var m = Math.floor(s / 60), r = s % 60;
    return m + ":" + (r < 10 ? "0" : "") + r;
  }}
  function clock(ts) {{
    if (!ts) return "";
    var d = new Date(ts * 1000);
    return d.toLocaleTimeString([], {{ hour: "2-digit", minute: "2-digit", second: "2-digit" }});
  }}
  function api(path, opts) {{
    opts = opts || {{}};
    opts.headers = Object.assign({{ "X-Remote-Secret": secret }}, opts.headers || {{}});
    return fetch(path, opts).then(function (r) {{
      if (r.status === 401) {{
        secret = null;
        try {{ sessionStorage.removeItem(KEY); }} catch (e) {{}}
        gate.style.display = "block";
        setErr("Wrong secret.");
        setBusy(true);
      }}
      return r;
    }});
  }}
  function tag(kind, label) {{
    return "<span class='tag " + kind + "'>" + label + "</span> ";
  }}
  function paintRules(d) {{
    var th = d.thresholds || {{}};
    var avgW = secsLabel(th.gapAvgS || 120);
    var vocW = secsLabel(th.extremaS || 7200);
    var frac = th.panelMinFrac != null ? th.panelMinFrac : "—";
    var skip = th.clearSkip != null ? th.clearSkip : "—";
    var lines = [
      tag("live", "live") + "Panel, Victron out, watts from BLE",
      tag("calc", "calc") + "Gap now = panel − out",
      tag("calc", "calc") + avgW + " avg gap " + fmt(d.avgGap, 0, " V"),
      tag("calc", "calc") + vocW + " Voc " + fmt(d.vocGap, 0, " V") + " = max panel − max out (" + fmt(th.maxPanel2h, 0, " V") + " − " + fmt(th.maxOut2h, 0, " V") + ")",
      tag("calc", "calc") + "Panel floor " + fmt(th.panelMinV, 0, " V") + " = " + frac + " × " + vocW + " max panel (frac hardcoded)",
      tag("calc", "calc") + "Envelope skip " + fmt(th.envelopeW, 0, " W") + " = " + skip + " × " + fmt(th.clearSkyW, 0, " W") + " this hour (frac + table hardcoded)",
      tag("calc", "calc") + "Out " + fmt(th.outMinV, 0, " V") + "–" + fmt(th.outMaxV, 0, " V") + " = " + (th.outMinFrac != null ? th.outMinFrac : "—") + "–" + (th.outMaxFrac != null ? th.outMaxFrac : "—") + " × " + (th.systemVoltageV != null ? ("0xEDEF " + fmt(th.systemVoltageV, 0, " V")) : (vocW + " max out " + fmt(th.maxOut2h, 0, " V"))),
      tag("rule", "hardcoded") + (th.maxPerHour != null ? th.maxPerHour : "—") + " / hour, " + secsLabel(th.cooldownS || 0) + " cooldown, hold " + (th.holdS != null ? th.holdS : "—") + " s",
      tag("rule", "hardcoded") + "Voc margin " + (th.gapMarginV != null ? th.gapMarginV : "—") + " V, min Voc gap " + (th.minDeltaV != null ? th.minDeltaV : "—") + " V"
    ];
    document.getElementById("rules").innerHTML = lines.map(function (s) {{ return "<div>" + s + "</div>"; }}).join("");
  }}
  function paintBanner(d) {{
    var cool = Math.max(0, Math.ceil((coolUntil - Date.now()) / 1000));
    var cls = "banner", kick = "Watching", head = d.chargeState || "Live", line = d.pulseWhy || "";
    if (d.busy) {{ cls += " work"; kick = "Pulsing"; head = "Off → on"; }}
    else if (cool > 0) {{ cls += " cool"; kick = "Cooldown"; head = mmss(cool); }}
    else if (d.pulseCandidate) {{
      cls += " ready"; kick = "Ready to pulse";
      head = d.holdRemainingS > 0 ? ("Hold " + d.holdRemainingS + "s") : "Auto pulse";
    }}
    banner.className = cls;
    kicker.textContent = kick;
    headline.textContent = head;
    why.textContent = line;
  }}
  function render(d) {{
    last = d;
    coolUntil = Date.now() + Math.max(0, (d.cooldownRemainingS || 0) * 1000);
    document.getElementById("nPv").textContent = fmt(d.panelVoltage, 1, " V");
    document.getElementById("nOut").textContent = fmt(d.outputVoltage != null ? d.outputVoltage : d.batteryVoltage, 1, " V");
    document.getElementById("nDv").textContent = fmt(d.deltaVoltage, 0, " V");
    document.getElementById("nW").textContent = fmt(d.solarPowerW, 0, " W");
    document.getElementById("nAvg").textContent = fmt(d.avgGap, 0, " V");
    document.getElementById("nVoc").textContent = fmt(d.vocGap, 0, " V");
    var th = d.thresholds || {{}};
    if (th.gapAvgS) document.getElementById("kAvg").innerHTML = secsLabel(th.gapAvgS) + " avg <span class='tag calc'>calc</span>";
    if (th.extremaS) document.getElementById("kVoc").innerHTML = secsLabel(th.extremaS) + " Voc <span class='tag calc'>calc</span>";
    paintRules(d);
    document.getElementById("tPv").className = "tile" + (d.pulseCandidate ? " warn" : "");
    document.getElementById("tDv").className = "tile" + (d.pulseCandidate ? " warn" : "");
    document.getElementById("tAvg").className = "tile" + (d.pulseCandidate ? " warn" : "");
    paintBanner(d);
    var hist = document.getElementById("hist");
    var rows = (d.pulses || []).slice().reverse();
    hist.innerHTML = rows.length ? rows.map(function (p) {{
      var cls = p.success ? "ok" : "fail";
      var reason = (p.reason || "").replace(/</g, "");
      return "<li><span class='"+cls+"'>" + clock(p.at) + "</span> · " + reason + (p.success ? "" : " · failed") + "</li>";
    }}).join("") : "<li>None yet this run</li>";
    setBusy(!!d.busy);
    gate.style.display = "none";
  }}
  function tick() {{
    if (last) paintBanner(last);
  }}
  function load() {{
    if (!secret) {{ setBusy(true); setErr("Enter the remote secret."); return; }}
    api("/charger/status").then(function (r) {{ return r.json().then(function (d) {{ return {{ r: r, d: d }}; }}); }})
      .then(function (x) {{
        if (x.r.status === 401) return;
        if (x.r.ok) {{ setErr(""); render(x.d); }}
        else setErr(x.d.error || ("Status " + x.r.status));
      }})
      .catch(function () {{ setErr("Can't reach the laptop."); }});
  }}
  function cmd(action) {{
    if (!secret) return;
    setBusy(true);
    banner.className = "banner work";
    kicker.textContent = "Working";
    headline.textContent = action === "restart" ? "Pulsing…" : "Command…";
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
    secretInput.value = "";
    load();
  }}
  document.getElementById("btnUnlock").onclick = unlock;
  secretInput.addEventListener("keydown", function (e) {{ if (e.key === "Enter") unlock(); }});
  document.getElementById("btnPulse").onclick = function () {{ cmd("restart"); }};
  document.getElementById("btnOn").onclick = function () {{ cmd("on"); }};
  document.getElementById("btnOff").onclick = function () {{ cmd("off"); }};
  document.getElementById("btnRead").onclick = function () {{ cmd("read"); }};
  if (secret) load();
  setInterval(function () {{ if (secret) load(); }}, 4000);
  setInterval(tick, 250);
}})();
</script>
</body>
</html>
"""
