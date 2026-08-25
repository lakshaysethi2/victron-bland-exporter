from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time

from . import client, protocol as P


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
    print(json.dumps({"success": r.success, "mode": r.mode, "message": r.message, "notifies": r.notifies[-12:]}, indent=2))
    return 0 if r.success else 1


async def _cmd_onoff(args: argparse.Namespace, on: bool) -> int:
    r = await client.set_mode(_mac(args), on)
    print(json.dumps({"success": r.success, "mode": r.mode, "message": r.message, "notifies": r.notifies[-12:]}, indent=2))
    return 0 if r.success else 1


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
    page_html = render_page(public_host())
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
        live[addr] = {
            "mac": addr,
            "rssi": parsed.rssi,
            "model_id": parsed.model_id,
            "last_seen": time.time(),
            **parsed.data,
        }

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

    def deny() -> web.Response:
        return web.json_response({"error": "unauthorized"}, status=401)

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
        snap = {**last, "ok": True, "schedule": cfg.get("schedule") or {}}
        if row:
            snap.update(
                {
                    "solarPowerW": row.get("solar_power_w"),
                    "batteryVoltage": row.get("battery_voltage"),
                    "batteryCurrent": row.get("battery_current"),
                    "chargeState": row.get("charge_state"),
                    "yieldTodayWh": row.get("yield_today_wh"),
                    "rssi": row.get("rssi"),
                    "lastBleAdAt": int(row["last_seen"] * 1000),
                }
            )
        return web.json_response(snap)

    async def handle_charger(request: web.Request) -> web.Response:
        await require(request)
        body = await request.json()
        action = str(body.get("action", "")).lower()
        target = str(body.get("mac") or mac)
        async with lock:
            scan_paused.set()
            try:
                await asyncio.wait_for(scan_idle.wait(), timeout=5)
            except asyncio.TimeoutError:
                pass
            await asyncio.sleep(0.35)
            try:
                if action == "on":
                    r = await client.set_mode(target, True)
                elif action == "off":
                    r = await client.set_mode(target, False)
                elif action == "read":
                    r = await client.read_mode(target)
                else:
                    return web.json_response({"error": "action must be on|off|read"}, status=400)
            except Exception as e:
                logging.getLogger("mppt_ble").exception("charger %s failed", action)
                r = client.SessionResult(False, None, str(e), [])
            finally:
                scan_paused.clear()
        if action in ("on", "off"):
            want_on = action == "on"
            deadline = time.time() + 10
            while time.time() < deadline:
                row = fresh_row(target)
                if row:
                    st = str(row.get("charge_state") or "")
                    watts = row.get("solar_power_w")
                    if want_on and (st in ("BULK", "ABSORPTION", "FLOAT") or (isinstance(watts, (int, float)) and watts > 5)):
                        r = client.SessionResult(True, r.mode or 1, f"Instant Readout {st or watts}W", r.notifies)
                        break
                    if (not want_on) and (st == "OFF" or watts == 0):
                        r = client.SessionResult(True, 4, f"Instant Readout {st or '0W'}", r.notifies)
                        break
                await asyncio.sleep(0.6)
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

    app = web.Application()
    async def handle_metrics(_request: web.Request) -> web.Response:
        now = time.time()
        fresh = [r for r in live.values() if (now - float(r["last_seen"])) * 1000 <= FRESH_MS]
        charge_num = {"OFF": 0, "BULK": 3, "ABSORPTION": 4, "FLOAT": 5}
        lines = [
            "# HELP mppt_exporter_up Laptop charger-control exporter is up",
            "# TYPE mppt_exporter_up gauge",
            "mppt_exporter_up 1",
            "# HELP victron_devices_total Number of Victron devices with a fresh Instant Readout",
            "# TYPE victron_devices_total gauge",
            f"victron_devices_total {len(fresh)}",
            "# HELP victron_up Instant Readout is fresh",
            "# TYPE victron_up gauge",
            "# HELP victron_solar_power_watts Instant Readout PV power",
            "# TYPE victron_solar_power_watts gauge",
            "# HELP victron_battery_voltage_volts Instant Readout battery voltage",
            "# TYPE victron_battery_voltage_volts gauge",
            "# HELP victron_battery_current_amps Instant Readout battery current",
            "# TYPE victron_battery_current_amps gauge",
            "# HELP victron_yield_today_wh Yield today",
            "# TYPE victron_yield_today_wh gauge",
            "# HELP victron_charge_state Charge state (3 bulk 4 absorption 5 float)",
            "# TYPE victron_charge_state gauge",
            "# HELP victron_rssi_dbm Advertisement RSSI",
            "# TYPE victron_rssi_dbm gauge",
        ]
        for row in fresh:
            model = f"Victron-0x{int(row.get('model_id') or 0):X}"
            dtype = row.get("device_type") or "mppt"
            labels = f'device="{model}",mac="{row["mac"]}",type="{dtype}"'
            lines.append(f"victron_up{{{labels}}} 1")
            mapping = [
                ("victron_solar_power_watts", row.get("solar_power_w")),
                ("victron_battery_voltage_volts", row.get("battery_voltage")),
                ("victron_battery_current_amps", row.get("battery_current")),
                ("victron_yield_today_wh", row.get("yield_today_wh")),
                ("victron_rssi_dbm", row.get("rssi")),
            ]
            for name, val in mapping:
                if val is not None:
                    lines.append(f"{name}{{{labels}}} {val}")
            st = row.get("charge_state")
            if isinstance(st, str) and st in charge_num:
                lines.append(f"victron_charge_state{{{labels}}} {charge_num[st]}")
        body = "\n".join(lines) + "\n"
        return web.Response(text=body, content_type="text/plain; version=0.0.4")

    app.router.add_get("/", handle_page)
    app.router.add_get("/charger", handle_page)
    app.router.add_get("/charger/status", handle_status)
    app.router.add_post("/charger", handle_charger)
    app.router.add_get("/metrics", handle_metrics)
    print(f"listening on http://{host}:{port}/ keys={len(keys)}", flush=True)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host, port)
    await site.start()
    asyncio.create_task(scan_loop())
    while True:
        await asyncio.sleep(3600)


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
    sp = sub.add_parser("serve")
    with_mac(sp)
    sp.add_argument("--bind", default="127.0.0.1:5340")

    args = p.parse_args()
    if args.cmd == "scan":
        fn = _cmd_scan(args)
    elif args.cmd == "read":
        fn = _cmd_read(args)
    elif args.cmd == "on":
        fn = _cmd_onoff(args, True)
    elif args.cmd == "off":
        fn = _cmd_onoff(args, False)
    else:
        fn = _cmd_serve(args)
    raise SystemExit(asyncio.run(fn))


if __name__ == "__main__":
    main()
