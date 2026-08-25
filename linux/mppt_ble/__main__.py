from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys

from . import client, protocol as P


def _mac(args: argparse.Namespace) -> str:
    mac = (args.mac or "").strip()
    if not mac:
        sys.exit("--mac is required (DC:AD:B0:54:DB:4E)")
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

    mac = _mac(args)
    bind = args.bind
    host, _, port_s = bind.rpartition(":")
    port = int(port_s)
    host = host.strip("[]") or "127.0.0.1"
    lock = asyncio.Lock()

    async def handle_status(_request: web.Request) -> web.Response:
        return web.json_response({"mac": mac, "ok": True})

    async def handle_charger(request: web.Request) -> web.Response:
        body = await request.json()
        action = str(body.get("action", "")).lower()
        target = str(body.get("mac") or mac)
        async with lock:
            if action == "on":
                r = await client.set_mode(target, True)
            elif action == "off":
                r = await client.set_mode(target, False)
            elif action == "read":
                r = await client.read_mode(target)
            else:
                return web.json_response({"error": "action must be on|off|read"}, status=400)
        return web.json_response(
            {
                "accepted": True,
                "success": r.success,
                "action": action,
                "mac": target,
                "mode": r.mode,
                "modeText": P.mode_text(r.mode),
                "message": r.message,
            },
            status=200 if r.success else 502,
        )

    app = web.Application()
    app.router.add_get("/charger/status", handle_status)
    app.router.add_post("/charger", handle_charger)
    print(f"listening on http://{host}:{port}/charger", flush=True)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host, port)
    await site.start()
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
