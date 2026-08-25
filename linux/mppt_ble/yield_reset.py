"""Maximise yield: pulse Victron off/on when the power-station MPPT looks stuck.

Weather vs stuck:
  Bright noon ~1600 W. Partly cloudy at the same hour often 900–1000 W.
  A grey spell may only do 400–600 W — that is weather, not a stuck station.
  After a cloud the station MPPT can stay low even when the sky opens.
  Pulse only if we recently saw a higher peak and watts stay down.

Always re-enables after a short OFF (default 4 s). Never leave the charger off.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.request import urlopen

log = logging.getLogger("mppt_yield")

POWER_RE = re.compile(r"^victron_solar_power_watts(?:\{[^}]*\})?\s+([0-9.]+)\s*$", re.M)

DEFAULT_CLEAR = {
    6: 50,
    7: 200,
    8: 500,
    9: 800,
    10: 1100,
    11: 1400,
    12: 1600,
    13: 1600,
    14: 1450,
    15: 1200,
    16: 850,
    17: 450,
    18: 120,
    19: 30,
}


@dataclass
class ResetPolicy:
    clear_sky: dict[int, float] = field(default_factory=lambda: dict(DEFAULT_CLEAR))
    partly_cloudy_factor: float = 0.6
    overcast_factor: float = 0.35
    bright_ratio: float = 0.75
    partly_ratio: float = 0.45
    stuck_fraction: float = 0.55
    hold_s: float = 50.0
    off_s: float = 4.0
    cooldown_s: float = 12 * 60.0
    min_peak_w: float = 120.0
    daytime_start: int = 7 * 60
    daytime_end: int = 18 * 60
    peak_window_s: float = 20 * 60.0


@dataclass
class ResetState:
    peak_w: float = 0.0
    peak_at: float = 0.0
    below_since: float | None = None
    last_pulse_at: float = 0.0
    last_watts: float | None = None
    last_action: str = "idle"
    regime: str = "unknown"
    expected_w: float = 0.0


def load_policy(path: str | None) -> ResetPolicy:
    p = ResetPolicy()
    if not path:
        return p
    data = json.loads(Path(path).read_text())
    raw = data.get("clear_sky_watts_by_hour") or {}
    if raw:
        p.clear_sky = {int(k): float(v) for k, v in raw.items()}
    p.partly_cloudy_factor = float(data.get("partly_cloudy_factor", p.partly_cloudy_factor))
    p.overcast_factor = float(data.get("overcast_factor", p.overcast_factor))
    p.bright_ratio = float(data.get("bright_if_peak_vs_clear_at_least", p.bright_ratio))
    p.partly_ratio = float(data.get("partly_if_peak_vs_clear_at_least", p.partly_ratio))
    p.stuck_fraction = float(data.get("stuck_fraction_of_peak", p.stuck_fraction))
    p.hold_s = float(data.get("hold_s", p.hold_s))
    p.off_s = float(data.get("off_s", p.off_s))
    p.cooldown_s = float(data.get("cooldown_s", p.cooldown_s))
    p.min_peak_w = float(data.get("min_peak_w", p.min_peak_w))
    p.peak_window_s = float(data.get("peak_window_s", p.peak_window_s))
    if "daytime_start_hour" in data:
        p.daytime_start = int(data["daytime_start_hour"]) * 60
    if "daytime_end_hour" in data:
        p.daytime_end = int(data["daytime_end_hour"]) * 60
    return p


def minutes_of_day(ts: float) -> int:
    lt = time.localtime(ts)
    return lt.tm_hour * 60 + lt.tm_min


def is_daytime(ts: float, policy: ResetPolicy) -> bool:
    m = minutes_of_day(ts)
    return policy.daytime_start <= m < policy.daytime_end


def clear_sky_watts(ts: float, policy: ResetPolicy) -> float:
    """Linear interpolation between whole local hours."""
    lt = time.localtime(ts)
    h = lt.tm_hour
    frac = lt.tm_min / 60.0
    a = policy.clear_sky.get(h, 0.0)
    b = policy.clear_sky.get(h + 1, a)
    return a + (b - a) * frac


def classify_weather(peak_w: float, clear_w: float, policy: ResetPolicy) -> tuple[str, float]:
    """Return (regime, expected_watts) for this hour given recent peak."""
    if clear_w <= 1:
        return "unknown", peak_w
    ratio = peak_w / clear_w
    if ratio >= policy.bright_ratio:
        return "bright", clear_w * 0.9
    if ratio >= policy.partly_ratio:
        return "partly", max(peak_w, clear_w * policy.partly_cloudy_factor)
    return "overcast", max(peak_w, clear_w * policy.overcast_factor)


def ingest(state: ResetState, ts: float, watts: float, policy: ResetPolicy) -> ResetState:
    if ts - state.peak_at > policy.peak_window_s:
        state.peak_w = watts
        state.peak_at = ts
    elif watts > state.peak_w:
        state.peak_w = watts
        state.peak_at = ts
    clear = clear_sky_watts(ts, policy)
    state.regime, state.expected_w = classify_weather(state.peak_w, clear, policy)
    state.last_watts = watts
    return state


def should_pulse(state: ResetState, ts: float, watts: float, policy: ResetPolicy) -> bool:
    if not is_daytime(ts, policy):
        state.below_since = None
        return False
    if ts - state.last_pulse_at < policy.cooldown_s:
        return False
    if state.peak_w < policy.min_peak_w:
        state.below_since = None
        return False
    # Stuck = well below what we recently proved this weather can do.
    # Overcast 400–600 W all morning is not stuck.
    threshold = max(state.peak_w * policy.stuck_fraction, min(state.expected_w * 0.4, state.peak_w * 0.7))
    if watts >= threshold:
        state.below_since = None
        return False
    if state.below_since is None:
        state.below_since = ts
        return False
    return (ts - state.below_since) >= policy.hold_s


def fetch_watts(metrics_url: str, timeout: float = 5.0) -> float | None:
    with urlopen(metrics_url, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
    m = POWER_RE.search(body)
    if not m:
        return None
    return float(m.group(1))


async def pulse(mac: str, off_s: float) -> str:
    from . import client

    off = await client.set_mode(mac, False)
    log.info("OFF: %s", off.message)
    await asyncio.sleep(off_s)
    on = await client.set_mode(mac, True)
    log.info("ON: %s", on.message)
    if not on.success:
        await asyncio.sleep(1.0)
        on2 = await client.set_mode(mac, True)
        log.info("ON retry: %s", on2.message)
        return f"off={off.success} on={on2.success} {on2.message}"
    return f"off={off.success} on={on.success} {on.message}"


async def loop(args: argparse.Namespace) -> None:
    policy = load_policy(args.config)
    policy.hold_s = args.hold if args.hold is not None else policy.hold_s
    policy.off_s = args.off_seconds if args.off_seconds is not None else policy.off_s
    policy.cooldown_s = args.cooldown if args.cooldown is not None else policy.cooldown_s
    state = ResetState()
    log.info(
        "yield-reset mac=%s metrics=%s off=%.1fs hold=%.0fs cooldown=%.0fs config=%s",
        args.mac,
        args.metrics,
        policy.off_s,
        policy.hold_s,
        policy.cooldown_s,
        args.config,
    )
    while True:
        ts = time.time()
        try:
            watts = fetch_watts(args.metrics)
        except Exception as e:
            log.warning("metrics fetch failed: %s", e)
            watts = None
        if watts is None:
            await asyncio.sleep(args.poll)
            continue
        ingest(state, ts, watts, policy)
        log.info(
            "watts=%.0f peak=%.0f expected=%.0f regime=%s",
            watts,
            state.peak_w,
            state.expected_w,
            state.regime,
        )
        if should_pulse(state, ts, watts, policy):
            log.warning(
                "stuck low: watts=%.0f peak=%.0f expected=%.0f (%s) — OFF %.1fs then ON",
                watts,
                state.peak_w,
                state.expected_w,
                state.regime,
                policy.off_s,
            )
            if args.dry_run:
                msg = "dry-run skip"
            else:
                msg = await pulse(args.mac, policy.off_s)
            state.last_pulse_at = time.time()
            state.below_since = None
            state.last_action = msg
            log.info("pulse done: %s", msg)
        await asyncio.sleep(args.poll)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    here = Path(__file__).resolve().parent.parent / "yield_config.json"
    p = argparse.ArgumentParser(description="Pulse Victron MPPT when watts look stuck after clouds")
    p.add_argument("--mac", required=True)
    p.add_argument("--metrics", default="http://127.0.0.1:5338/metrics")
    p.add_argument("--config", default=str(here) if here.is_file() else "")
    p.add_argument("--poll", type=float, default=10.0)
    p.add_argument("--hold", type=float, default=None)
    p.add_argument("--off-seconds", type=float, default=None)
    p.add_argument("--cooldown", type=float, default=None)
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()
    asyncio.run(loop(args))


if __name__ == "__main__":
    main()
