from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time

from . import client, protocol as P
from .restart import cooldown_ok, pulse, wants_restart


def _mac(args: argparse.Namespace) -> str:
    mac = (args.mac or os.environ.get("MPPT_MAC") or "").strip()
    if not mac:
        sys.exit("--mac or MPPT_MAC is required")
    return mac


async def _cmd_scan(_args: argparse.Namespace) -> int:
    rows = await client.scan()
    print(json.dumps(rows, indent=2))
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
    from .http_page import render_page
    from .readout import VICTRON_COMPANY_ID, parse_advertisement

    cfg = load_devices()
    mac = (args.mac or cfg.get("mac") or "").strip()
    if not mac:
        print("set MPPT_MAC, pass --mac, or put mac in ~/.config/mppt/devices.json", file=sys.stderr)
        return 2
    try:
        page_html = render_page(public_host())
    except Exception:
        page_html = "<p>mppt_ble</p>"
    keys = {k.upper(): str(v).lower() for k, v in (cfg.get("keys") or {}).items()}
    bind = args.bind
    host, _, port_s = bind.rpartition(":")
    port = int(port_s)
    host = host.strip("[]") or "127.0.0.1"
    expected = configured_secret()
    if not expected:
        print("MPPT_REMOTE_SECRET is empty — refusing to serve", file=sys.stderr)
        return 2
    lock = asyncio.Lock()
    last: dict = {"mac": mac, "host": "laptop"}
    live: dict[str, dict] = {}
    scan_paused = asyncio.Event()
    scan_idle = asyncio.Event()
    scan_paused.clear()
    scan_idle.set()

    def on_detect(device, adv) -> None:
        md = (adv.manufacturer_data or {}).get(VICTRON_COMPANY_ID)
        if not md:
            return
        addr = device.address.upper()
        parsed = parse_advertisement(addr, bytes(md), int(getattr(adv, "rssi", 0) or 0), keys.get(addr))
        if parsed is None:
            return
        live[addr] = {"mac": addr, "rssi": parsed.rssi, "model_id": parsed.model_id, "last_seen": time.time(), **parsed.data}

    async def scan_loop() -> None:
        log = logging.getLogger("mppt_ble")
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

    async def require(request: web.Request) -> None:
        if not secret_ok(request.headers, expected):
            raise web.HTTPUnauthorized(text='{"error":"unauthorized"}', content_type="application/json")

    async def handle_page(_request: web.Request) -> web.Response:
        return web.Response(text=page_html, content_type="text/html")

    def fresh_row(addr: str) -> dict | None:
        row = live.get(addr.upper())
        if not row:
            return None
        if (time.time() - float(row["last_seen"])) * 1000 > FRESH_MS:
            return None
        return row

    async def handle_status(request: web.Request) -> web.Response:
        await require(request)
        row = fresh_row(mac)
        snap = {**last, "ok": True}
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
            body = await request.text()
        action = str(body.get("action", "") if isinstance(body, dict) else "").lower()
        if wants_restart(body):
            action = "restart"
        target = str((body.get("mac") if isinstance(body, dict) else None) or mac)
        async with lock:
            scan_paused.set()
            try:
                await asyncio.wait_for(scan_idle.wait(), timeout=5)
            except asyncio.TimeoutError:
                pass
            await asyncio.sleep(0.35)
            try:
                if action == "restart":
                    if not cooldown_ok():
                        return web.json_response({"error": "restart cooldown"}, status=429)
                    off, r = await pulse(target)
                    r = client.SessionResult(r.success, r.mode, f"restart off={off.success} on={r.success} {r.message}", r.notifies)
                elif action == "on":
                    r = await client.set_mode(target, True)
                elif action == "off":
                    r = await client.set_mode(target, False)
                elif action == "read":
                    r = await client.read_mode(target)
                else:
                    return web.json_response({"error": "action must be on|off|read|restart"}, status=400)
            except Exception as e:
                logging.getLogger("mppt_ble").exception("charger %s failed", action)
                r = client.SessionResult(False, None, str(e), [])
            finally:
                scan_paused.clear()
        payload = {
            "accepted": True,
            "success": r.success,
            "action": action,
            "mac": target,
            "mode": r.mode,
            "modeText": P.mode_text(r.mode),
            "message": r.message,
            "host": "laptop",
        }
        last.update(payload)
        return web.json_response(payload, status=200 if r.success else 502)

    async def handle_metrics(_request: web.Request) -> web.Response:
        now = time.time()
        fresh = [r for r in live.values() if (now - float(r["last_seen"])) * 1000 <= FRESH_MS]
        lines = [
            "# TYPE mppt_exporter_up gauge",
            "mppt_exporter_up 1",
            f"victron_devices_total {len(fresh)}",
        ]
        for row in fresh:
            model = f"Victron-0x{int(row.get('model_id') or 0):X}"
            labels = f'device="{model}",mac="{row[\"mac\"]}",type="mppt"'
            if row.get("solar_power_w") is not None:
                lines.append(f"victron_solar_power_watts{{{labels}}} {row['solar_power_w']}")
        return web.Response(text="\\n".join(lines) + "\\n", content_type="text/plain; version=0.0.4")

    app = web.Application()
    app.router.add_get("/", handle_page)
    app.router.add_get("/charger", handle_page)
    app.router.add_get("/charger/status", handle_status)
    app.router.add_post("/charger", handle_charger)
    app.router.add_get("/metrics", handle_metrics)
    print(f"listening on http://{host}:{port}/ keys={len(keys)}", flush=True)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, host, port).start()
    asyncio.create_task(scan_loop())
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
    fn = {
        "scan": _cmd_scan(args),
        "read": _cmd_read(args),
        "on": _cmd_onoff(args, True),
        "off": _cmd_onoff(args, False),
        "restart": _cmd_restart(args),
        "serve": _cmd_serve(args),
    }[args.cmd]
    raise SystemExit(asyncio.run(fn))


if __name__ == "__main__":
    main()
