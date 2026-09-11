"""OFF then ON for a stuck SmartSolar. Used by HTTP restart and yield_reset."""

from __future__ import annotations

import json
import time

COOLDOWN_S = 600.0
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
    from . import client

    off = await client.set_mode(mac, False)
    import asyncio

    await asyncio.sleep(off_s)
    on = await client.set_mode(mac, True)
    if not on.success:
        await asyncio.sleep(1)
        on = await client.set_mode(mac, True)
    return off, on
