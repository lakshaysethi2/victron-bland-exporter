from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from pathlib import Path

from . import client, protocol as P
from .metrics import render_metrics
from .node_metrics import fetch_node_metrics, node_exporter_url
from .restart import pulse
from .yield_reset import ResetState, ingest, load_policy, should_pulse

log = logging.getLogger("mppt_ble")


def _mac(args: argparse.Namespace) -> str:
    mac = (args.mac or os.environ.get("MPPT_MAC") or "").strip()
    if not mac:
        sys.exit("--mac or MPPT_MAC is required")
    return mac


async def _cmd_scan(_args: argparse.Namespace) -> int:
    print(json.dumps(await client.scan(), indent=2))
    return 0


async def _cmd_read(args: argparse.Namespace) -> int:
    r = await client.read_mode(_mac(args))
    print(json.dumps({"success": r.success, "mode": r.mode, "message": r.message}, indent=2))
    return 0 if r.success else 1


async def _cmd_onoff(args: argparse.Namespace, on: bool) -> int:
    r = await client.set_mode(_mac(args), on)
    print(json.dumps({"success": r.success, "mode": r.mode, "message": r.message}, indent=2))
    return 0 if r.success else 1


async def _cmd_restart(args: argparse.Namespace) -> int:
    off, on = await pulse(_mac(args))
    print(json.dumps({"off": off.success, "on": on.success, "message": on.message}, indent=2))
    return 0 if on.success else 1


async def _cmd_serve(args: argparse.Namespace) -> int:
    from aiohttp import web
    from bleak import BleakScanner

    from .auth import configured_secret, secret_ok
    from .config import FRESH_MS, load_devices, public_host
    from .readout import VICTRON_COMPANY_ID, parse_advertisement

    cfg = load_devices()
    mac = (args.mac or cfg.get("mac") or "").strip()
    if not mac:
        print("set MPPT_MAC or ~/.config/mppt/devices.json mac", file=sys.stderr)
        return 2
    try:
        from .http_page import render_page

        page_html = render_page(public_host())
    except Exception:
        page_html = "<p>mppt_ble</p>"
    keys = {k.upper(): str(v).lower() for k, v in (cfg.get("keys") or {}).items()}
    host, _, port_s = args.bind.rpartition(":")
    port = int(port_s)
    host = host.strip("[]") or "127.0.0.1"
    expected = configured_secret()
    if not expected:
        print("MPPT_REMOTE_SECRET is empty — refusing to serve", file=sys.stderr)
        return 2
    node_url = node_exporter_url()

    lock = asyncio.Lock()
    last: dict = {"mac": mac, "host": "linux"}
    live: dict[str, dict] = {}
    scan_paused = asyncio.Event()
    scan_idle = asyncio.Event()
    scan_paused.clear()
    scan_idle.set()
    policy_path = str(Path(__file__).resolve().parent.parent / "yield_config.json")
    policy = load_policy(policy_path if Path(policy_path).is_file() else None)
    reset_state = ResetState()

    def on_detect(device, adv) -> None:
        md = (adv.manufacturer_data or {}).get(VICTRON_COMPANY_ID)
        if not md:
            return
        addr = device.address.upper()
        parsed = parse_advertisement(addr, bytes(md), int(getattr(adv, "rssi", 0) or 0), keys.get(addr))
        if parsed is None:
            return
        live[addr] = {
            "mac": addr,
            "rssi": parsed.rssi,
            "model_id": parsed.model_id,
            "last_seen": time.time(),
            **parsed.data,
        }

    async def scan_loop() -> None:
        while True:
            if scan_paused.is_set():
                scan_idle.set()
                await asyncio.sleep(0.15)
                continue
            scan_idle.clear()
            try:
                async with BleakScanner(detection_callback=on_detect):
                    while not scan_paused.is_set():
                        await asyncio.sleep(0.15)
            except Exception as e:
                log.warning("scan: %s", e)
                await asyncio.sleep(1.0)
            finally:
                scan_idle.set()

    def fresh_row(addr: str) -> dict | None:
        row = live.get(addr.upper())
        if not row:
            return None
        if (time.time() - float(row["last_seen"])) * 1000 > FRESH_MS:
            return None
        return row

    async def do_pulse(target: str, reason: str) -> client.SessionResult:
        async with lock:
            scan_paused.set()
            try:
                await asyncio.wait_for(scan_idle.wait(), timeout=5)
            except asyncio.TimeoutError:
                pass
            await asyncio.sleep(0.35)
            try:
                off, on = await pulse(target)
                msg = f"{reason} off={off.success} on={on.success} {on.message}"
                log.warning("%s", msg)
                r = client.SessionResult(on.success, on.mode, msg, on.notifies)
            except Exception as e:
                log.exception("pulse failed")
                r = client.SessionResult(False, None, str(e), [])
            finally:
                scan_paused.clear()
        last.update({"action": "restart", "message": r.message, "success": r.success})
        return r

    async def watchdog() -> None:
        """Read live Instant Readout; do not HTTP-fetch /metrics."""
        while True:
            await asyncio.sleep(10)
            row = fresh_row(mac)
            if not row:
                continue
            watts = row.get("solar_power_w")
            if not isinstance(watts, (int, float)):
                continue
            ts = time.time()
            ingest(reset_state, ts, float(watts), policy)
            if not should_pulse(reset_state, ts, float(watts), policy):
                continue
            r = await do_pulse(mac, f"watchdog watts={watts:.0f}")
            if r.success:
                reset_state.last_pulse_at = time.time()
                reset_state.below_since = None

    async def require(request: web.Request) -> None:
        if not secret_ok(request.headers, expected):
            raise web.HTTPUnauthorized(text='{"error":"unauthorized"}', content_type="application/json")

    async def handle_page(_request: web.Request) -> web.Response:
        return web.Response(text=page_html, content_type="text/html")

    async def handle_status(request: web.Request) -> web.Response:
        await require(request)
        row = fresh_row(mac)
        snap = {**last, "ok": True, "watchdog": True}
        if row:
            snap.update(
                {
                    "solarPowerW": row.get("solar_power_w"),
                    "batteryVoltage": row.get("battery_voltage"),
                    "chargeState": row.get("charge_state"),
                    "lastBleAdAt": int(row["last_seen"] * 1000),
                }
            )
        return web.json_response(snap)

    async def handle_charger(request: web.Request) -> web.Response:
        await require(request)
        try:
            body = await request.json()
        except Exception:
            body = {}
        action = str(body.get("action", "") if isinstance(body, dict) else "").lower()
        target = str((body.get("mac") if isinstance(body, dict) else None) or mac)
        if action == "restart":
            r = await do_pulse(target, "manual restart")
        elif action in ("on", "off", "read"):
            async with lock:
                scan_paused.set()
                try:
                    await asyncio.wait_for(scan_idle.wait(), timeout=5)
                except asyncio.TimeoutError:
                    pass
                try:
                    if action == "on":
                        r = await client.set_mode(target, True)
                    elif action == "off":
                        r = await client.set_mode(target, False)
                    else:
                        r = await client.read_mode(target)
                finally:
                    scan_paused.clear()
        else:
            return web.json_response({"error": "action must be on|off|read|restart"}, status=400)
        payload = {
            "accepted": True,
            "success": r.success,
            "action": action,
            "mac": target,
            "mode": r.mode,
            "modeText": P.mode_text(r.mode),
            "message": r.message,
            "host": "linux",
        }
        last.update(payload)
        return web.json_response(payload, status=200 if r.success else 502)

    async def handle_metrics(_request: web.Request) -> web.Response:
        now = time.time()
        fresh = [r for r in live.values() if (now - float(r["last_seen"])) * 1000 <= FRESH_MS]
        return web.Response(text=render_metrics(fresh), content_type="text/plain; version=0.0.4")

    async def handle_node_metrics(_request: web.Request) -> web.Response:
        try:
            status, body, ctype = fetch_node_metrics(node_url)
        except Exception as e:
            log.warning("node-exporter proxy: %s", e)
            return web.Response(text="Bad Gateway\n", status=502, content_type="text/plain")
        return web.Response(text=body, status=status, content_type=ctype)

    app = web.Application()
    app.router.add_get("/", handle_page)
    app.router.add_get("/charger", handle_page)
    app.router.add_get("/charger/status", handle_status)
    app.router.add_post("/charger", handle_charger)
    app.router.add_get("/metrics", handle_metrics)
    app.router.add_get("/node/metrics", handle_node_metrics)
    print(
        f"listening on http://{host}:{port}/ watchdog=on node={\"on\" if node_url else \"off\"} keys={len(keys)}",
        flush=True,
    )
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, host, port).start()
    asyncio.create_task(scan_loop())
    asyncio.create_task(watchdog())
    while True:
        await asyncio.sleep(3600)
    return 0


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    p = argparse.ArgumentParser(prog="mppt_ble")
    sub = p.add_subparsers(dest="cmd", required=True)

    def with_mac(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("--mac", default="")

    with_mac(sub.add_parser("scan"))
    with_mac(sub.add_parser("read"))
    with_mac(sub.add_parser("on"))
    with_mac(sub.add_parser("off"))
    with_mac(sub.add_parser("restart"))
    sp = sub.add_parser("serve")
    with_mac(sp)
    sp.add_argument("--bind", default="127.0.0.1:5338")
    args = p.parse_args()
    cmds = {
        "scan": _cmd_scan,
        "read": _cmd_read,
        "on": lambda a: _cmd_onoff(a, True),
        "off": lambda a: _cmd_onoff(a, False),
        "restart": _cmd_restart,
        "serve": _cmd_serve,
    }
    raise SystemExit(asyncio.run(cmds[args.cmd](args)))


if __name__ == "__main__":
    main()
