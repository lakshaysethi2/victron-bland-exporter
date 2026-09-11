"""OFF then ON for a stuck SmartSolar. Used by HTTP restart and yield_reset."""

from __future__ import annotations

import asyncio
import json
import time

COOLDOWN_S = 300.0
OFF_S = 4.0

_last = 0.0


def grafana_firing(body: dict | str) -> bool:
    raw = json.dumps(body).lower() if isinstance(body, dict) else str(body).lower()
    compact = raw.replace(" ", "")
    if '"status":"resolved"' in compact and "firing" not in raw:
        return False
    if "shade-free-expect" in raw or "solar power below 500" in raw:
        return "firing" in raw
    return False


def wants_restart(body: dict | str) -> bool:
    if isinstance(body, dict) and str(body.get("action", "")).lower() == "restart":
        return True
    return grafana_firing(body)


def cooldown_ok(now: float | None = None) -> bool:
    global _last
    ts = time.time() if now is None else now
    if ts - _last < COOLDOWN_S:
        return False
    _last = ts
    return True


async def pulse(mac: str, off_s: float = OFF_S):
    """Always attempt ON, even if OFF or sleep fails."""
    from . import client

    off = client.SessionResult(False, None, "off not attempted", [])
    on = client.SessionResult(False, None, "on not attempted", [])
    try:
        off = await client.set_mode(mac, False)
        await asyncio.sleep(off_s)
    finally:
        on = await client.set_mode(mac, True)
        if not on.success:
            await asyncio.sleep(1)
            on = await client.set_mode(mac, True)
        if not on.success:
            await asyncio.sleep(1)
            on = await client.set_mode(mac, True)
    return off, on
