"""Remote charger shell. Secret is never in URLs."""

PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
<meta name="theme-color" content="#0f1410"/>
<title>MPPT</title>
<style>
  :root {
    --bg: #0f1410;
    --card: #1a211c;
    --ink: #eef3ea;
    --muted: #9aa894;
    --line: #2c362f;
    --on: #3d9a5a;
    --on-ink: #04140a;
    --off: #c45c3a;
    --off-ink: #1a0804;
    --accent: #e6c36a;
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; min-height: 100%; background: var(--bg); color: var(--ink);
    font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; }
  body { padding: 1.25rem 1rem 2.5rem; }
  .wrap { max-width: 26rem; margin: 0 auto; }
  header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 1rem; }
  h1 { font-size: 1.15rem; font-weight: 650; letter-spacing: .02em; margin: 0; }
  .host { color: var(--muted); font-size: .8rem; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 1.1rem 1.15rem; }
  label { display: block; color: var(--muted); font-size: .8rem; margin-bottom: .4rem; }
  input[type=password] {
    width: 100%; padding: .75rem .8rem; border-radius: 10px; border: 1px solid var(--line);
    background: #101610; color: var(--ink); font-size: 1rem;
  }
  .row { display: grid; grid-template-columns: 1fr 1fr; gap: .7rem; margin-top: .9rem; }
  button {
    width: 100%; border: 0; border-radius: 12px; padding: .85rem .9rem;
    font-size: 1rem; font-weight: 650; cursor: pointer;
  }
  button.primary { background: var(--accent); color: #1a1404; }
  button.on { background: var(--on); color: var(--on-ink); }
  button.off { background: var(--off); color: var(--off-ink); }
  button.ghost { background: transparent; color: var(--ink); border: 1px solid var(--line); font-weight: 550; }
  button:disabled { opacity: .5; cursor: wait; }
  .watts { font-size: 3.4rem; font-weight: 720; letter-spacing: -.04em; line-height: 1; }
  .watts span { font-size: 1.1rem; color: var(--muted); font-weight: 550; margin-left: .2rem; }
  .state { margin: .45rem 0 1rem; color: var(--accent); font-weight: 600; }
  .tile { background: #121813; border-radius: 12px; padding: .7rem .8rem; }
  .tile .k { color: var(--muted); font-size: .72rem; text-transform: uppercase; letter-spacing: .06em; }
  .tile .v { font-size: 1.15rem; font-weight: 650; margin-top: .15rem; }
  .msg { min-height: 1.3rem; color: var(--muted); font-size: .85rem; margin: .8rem 0 0; }
  .msg.err { color: #ef8b73; }
  details { margin-top: 1rem; color: var(--muted); font-size: .78rem; }
  pre { white-space: pre-wrap; word-break: break-all; color: #c5d0c0; }
  #ctl { display: none; }
  #ctl.show { display: block; }
  #login.hid { display: none; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>SmartSolar</h1>
    <div class="host">__PUBLIC_HOST__</div>
  </header>

  <form id="login" class="card" autocomplete="on">
    <label for="secret">Remote secret</label>
    <input id="secret" type="password" autocomplete="current-password" placeholder="Stored only in this tab"/>
    <div class="row" style="grid-template-columns:1fr">
      <button class="primary" type="submit">Unlock</button>
    </div>
    <p class="msg" id="loginMsg">Sent as X-Remote-Secret, never in the URL.</p>
  </form>

  <div id="ctl">
    <div class="card">
      <div class="watts" id="watts">—<span>W</span></div>
      <div class="state" id="chargeState">Waiting for BLE…</div>
      <div class="row">
        <div class="tile"><div class="k">Battery</div><div class="v" id="volts">—</div></div>
        <div class="tile"><div class="k">Current</div><div class="v" id="amps">—</div></div>
        <div class="tile"><div class="k">Today</div><div class="v" id="yield">—</div></div>
        <div class="tile"><div class="k">Window</div><div class="v" id="window">—</div></div>
      </div>
      <div class="row" style="margin-top:1rem">
        <button class="on" data-action="on" type="button">Charger ON</button>
        <button class="off" data-action="off" type="button">Charger OFF</button>
      </div>
      <div class="row" style="grid-template-columns:1fr">
        <button class="ghost" id="refresh" type="button">Refresh</button>
      </div>
      <p class="msg" id="msg"></p>
      <details>
        <summary>Raw status</summary>
        <pre id="out"></pre>
      </details>
    </div>
  </div>
</div>
<script>
const $ = (id) => document.getElementById(id);
function secret() { return sessionStorage.getItem("mpptSecret") || ""; }
function fmt(n, d, unit) {
  if (n === null || n === undefined || n === "") return "—";
  const x = Number(n);
  if (Number.isNaN(x)) return "—";
  return x.toFixed(d) + (unit ? " " + unit : "");
}
function age(ms) {
  if (!ms) return "";
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 5) return "just now";
  if (s < 90) return s + "s ago";
  return Math.round(s / 60) + "m ago";
}
function paint(j) {
  $("out").textContent = JSON.stringify(j, null, 2);
  const w = j.solarPowerW;
  $("watts").innerHTML = (w === null || w === undefined ? "—" : Math.round(w)) + "<span>W</span>";
  const st = j.chargeState || j.modeText || "Unknown";
  $("chargeState").textContent = st + (j.lastBleAdAt ? " · " + age(j.lastBleAdAt) : "");
  $("volts").textContent = fmt(j.batteryVoltage, 2, "V");
  $("amps").textContent = fmt(j.batteryCurrent, 1, "A");
  $("yield").textContent = j.yieldTodayWh != null ? Math.round(j.yieldTodayWh) + " Wh" : "—";
  const sch = j.schedule || {};
  $("window").textContent = sch.enabled
    ? ((sch.enable_time || "?") + "–" + (sch.disable_time || "?"))
    : "off";
  if (j.message) $("msg").textContent = j.message;
}
async function api(path, body) {
  const opt = { headers: { "X-Remote-Secret": secret() } };
  if (body) {
    opt.method = "POST";
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(path, opt);
  const t = await r.text();
  if (!r.ok) throw new Error(t);
  try { return JSON.parse(t); } catch { return t; }
}
function showCtl() {
  $("login").classList.add("hid");
  $("ctl").classList.add("show");
}
async function refresh() {
  const j = await api("/charger/status");
  paint(j);
  showCtl();
}
$("login").onsubmit = async (e) => {
  e.preventDefault();
  sessionStorage.setItem("mpptSecret", $("secret").value);
  $("loginMsg").textContent = "";
  try { await refresh(); }
  catch (err) { $("loginMsg").textContent = "Unlock failed"; $("loginMsg").className = "msg err"; }
};
document.querySelectorAll("button[data-action]").forEach((b) => {
  b.onclick = async () => {
    const action = b.dataset.action;
    if (action === "off" && !confirm("Turn the charger OFF?")) return;
    b.disabled = true;
    $("msg").textContent = action + "…";
    try {
      const j = await api("/charger", { action });
      paint(j);
      $("msg").textContent = (j.success ? "OK · " : "Failed · ") + (j.message || action);
    } catch (err) {
      $("msg").textContent = String(err);
      $("msg").className = "msg err";
    }
    b.disabled = false;
  };
});
$("refresh").onclick = () => refresh().catch((e) => { $("msg").textContent = String(e); });
if (secret()) refresh().catch(() => {});
setInterval(() => { if (secret() && $("ctl").classList.contains("show")) refresh().catch(() => {}); }, 4000);
</script>
</body>
</html>
"""


def render_page(public_host: str) -> str:
    host = (public_host or "local").strip() or "local"
    return PAGE.replace("__PUBLIC_HOST__", host)
