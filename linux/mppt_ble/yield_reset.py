"""Pulse the Victron off/on when local watts look stuck. Dashboards are display-only."""

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
PANEL_RE = re.compile(r"^victron_panel_voltage_volts(?:\{[^}]*\})?\s+([0-9.]+)\s*$", re.M)
BATT_RE = re.compile(r"^victron_battery_voltage_volts(?:\{[^}]*\})?\s+([0-9.]+)\s*$", re.M)

DEFAULT_CLEAR = {
    6: 50, 7: 200, 8: 500, 9: 800, 10: 1100, 11: 1400,
    12: 1600, 13: 1600, 14: 1450, 15: 1200, 16: 850, 17: 450, 18: 120, 19: 30,
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
    cooldown_s: float = 5 * 60.0
    min_peak_w: float = 120.0
    daytime_start: int = 7 * 60
    daytime_end: int = 18 * 60
    peak_window_s: float = 20 * 60.0
    shade_start: int = 11 * 60 + 30
    shade_end: int = 15 * 60 + 30
    shade_floor_w: float = 500.0
    shade_hold_s: float = 120.0
    # Cascade: panels → this Victron → downstream MPPT → cells.
    # High Vpv vs Victron output + low watts = sitting near Voc; pulse restarts the chain.
    local_mpp_panel_min_v: float = 120.0
    local_mpp_min_delta_v: float = 80.0
    local_mpp_battery_max_v: float = 52.0
    local_mpp_max_w: float = 1500.0
    local_mpp_hold_s: float = 30.0
    # False = only pulse from panel-voltage conditions (no watt-drop / shade-floor pulses).
    watt_only_pulses: bool = False


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
    local_mpp_since: float | None = None
    pulse_reason: str = ""
    last_status_log_at: float = 0.0


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
    p.local_mpp_panel_min_v = float(data.get("local_mpp_panel_min_v", p.local_mpp_panel_min_v))
    p.local_mpp_min_delta_v = float(data.get("local_mpp_min_delta_v", p.local_mpp_min_delta_v))
    p.local_mpp_battery_max_v = float(data.get("local_mpp_battery_max_v", p.local_mpp_battery_max_v))
    p.local_mpp_max_w = float(data.get("local_mpp_max_w", p.local_mpp_max_w))
    p.local_mpp_hold_s = float(data.get("local_mpp_hold_s", p.local_mpp_hold_s))
    p.watt_only_pulses = bool(data.get("watt_only_pulses", p.watt_only_pulses))
    return p


def minutes_of_day(ts: float) -> int:
    lt = time.localtime(ts)
    return lt.tm_hour * 60 + lt.tm_min


def is_daytime(ts: float, policy: ResetPolicy) -> bool:
    m = minutes_of_day(ts)
    return policy.daytime_start <= m < policy.daytime_end


def in_shade_window(ts: float, policy: ResetPolicy) -> bool:
    m = minutes_of_day(ts)
    return policy.shade_start <= m < policy.shade_end


def clear_sky_watts(ts: float, policy: ResetPolicy) -> float:
    lt = time.localtime(ts)
    h = lt.tm_hour
    frac = lt.tm_min / 60.0
    a = policy.clear_sky.get(h, 0.0)
    b = policy.clear_sky.get(h + 1, a)
    return a + (b - a) * frac


def classify_weather(peak_w: float, clear_w: float, policy: ResetPolicy) -> tuple[str, float]:
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


def voltage_delta(panel_v: float | None, battery_v: float | None) -> float | None:
    if panel_v is None or battery_v is None:
        return None
    return panel_v - battery_v


def pulse_why(
    watts: float | None,
    panel_v: float | None,
    battery_v: float | None,
    policy: ResetPolicy,
    ts: float,
    last_pulse_at: float,
) -> str:
    """Short reason the watchdog will or will not pulse. For the /charger page."""
    if not is_daytime(ts, policy):
        return "night"
    if panel_v is None:
        return "waiting for panel voltage"
    if panel_v < policy.local_mpp_panel_min_v:
        return f"panel {panel_v:.0f}V below {policy.local_mpp_panel_min_v:.0f}V"
    if battery_v is not None and battery_v > policy.local_mpp_battery_max_v:
        return f"output {battery_v:.0f}V already high"
    delta = voltage_delta(panel_v, battery_v)
    if delta is not None and delta < policy.local_mpp_min_delta_v:
        return f"gap {delta:.0f}V below {policy.local_mpp_min_delta_v:.0f}V"
    if watts is None:
        return "no watts yet"
    if watts >= policy.local_mpp_max_w:
        return f"{watts:.0f}W ≥ {policy.local_mpp_max_w:.0f}W skip"
    return "ready"


def local_mpp_stuck(
    watts: float,
    panel_v: float | None,
    battery_v: float | None,
    policy: ResetPolicy,
) -> bool:
    """Victron sitting near Voc: high Vpv, large Vpv−Vout, watts not high enough."""
    if panel_v is None or panel_v < policy.local_mpp_panel_min_v:
        return False
    if battery_v is not None and battery_v > policy.local_mpp_battery_max_v:
        return False
    delta = voltage_delta(panel_v, battery_v)
    if delta is not None and delta < policy.local_mpp_min_delta_v:
        return False
    return watts < policy.local_mpp_max_w


def should_pulse(
    state: ResetState,
    ts: float,
    watts: float,
    policy: ResetPolicy,
    panel_v: float | None = None,
    battery_v: float | None = None,
) -> bool:
    state.pulse_reason = ""
    if not is_daytime(ts, policy):
        state.below_since = None
        state.local_mpp_since = None
        return False
    if ts - state.last_pulse_at < policy.cooldown_s:
        return False

    if local_mpp_stuck(watts, panel_v, battery_v, policy):
        if state.local_mpp_since is None:
            state.local_mpp_since = ts
            return False
        if ts - state.local_mpp_since >= policy.local_mpp_hold_s:
            bat = f"{battery_v:.1f}V" if isinstance(battery_v, (int, float)) else "?"
            delta = voltage_delta(panel_v, battery_v)
            dtxt = f" dV={delta:.0f}V" if delta is not None else ""
            state.pulse_reason = f"local-mpp pv={panel_v:.1f}V out={bat}{dtxt} watts={watts:.0f}"
            return True
    else:
        state.local_mpp_since = None

    if not policy.watt_only_pulses:
        state.below_since = None
        return False

    if state.peak_w < policy.min_peak_w:
        state.below_since = None
        return False
    threshold = max(state.peak_w * policy.stuck_fraction, min(state.expected_w * 0.4, state.peak_w * 0.7))
    stuck = watts < threshold
    midday = (
        in_shade_window(ts, policy)
        and watts < policy.shade_floor_w
        and state.peak_w > policy.shade_floor_w
    )
    if not stuck and not midday:
        state.below_since = None
        return False
    if state.below_since is None:
        state.below_since = ts
        return False
    need = policy.hold_s if stuck else policy.shade_hold_s
    if (ts - state.below_since) >= need:
        kind = "stuck" if stuck else "shade"
        state.pulse_reason = f"{kind} watts={watts:.0f}"
        return True
    return False


def fetch_watts(metrics_url: str, timeout: float = 5.0) -> float | None:
    watts, _, _ = fetch_snapshot(metrics_url, timeout=timeout)
    return watts


def fetch_snapshot(metrics_url: str, timeout: float = 5.0) -> tuple[float | None, float | None, float | None]:
    with urlopen(metrics_url, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
    def one(rx: re.Pattern[str]) -> float | None:
        m = rx.search(body)
        return float(m.group(1)) if m else None
    return one(POWER_RE), one(PANEL_RE), one(BATT_RE)


async def pulse(mac: str, off_s: float) -> str:
    """Same guarantee as serve: OFF then always attempt ON."""
    from .restart import pulse as restart_pulse

    off, on = await restart_pulse(mac, off_s)
    log.info("OFF: %s", off.message)
    log.info("ON: %s", on.message)
    return f"off={off.success} on={on.success} {on.message}"


async def loop(args: argparse.Namespace) -> None:
    policy = load_policy(args.config)
    policy.hold_s = args.hold if args.hold is not None else policy.hold_s
    policy.off_s = args.off_seconds if args.off_seconds is not None else policy.off_s
    policy.cooldown_s = args.cooldown if args.cooldown is not None else policy.cooldown_s
    state = ResetState()
    log.info("yield-reset mac=%s metrics=%s off=%.1fs", args.mac, args.metrics, policy.off_s)
    while True:
        ts = time.time()
        try:
            watts, panel_v, battery_v = fetch_snapshot(args.metrics)
        except Exception as e:
            log.warning("metrics fetch failed: %s", e)
            watts = None
            panel_v = None
            battery_v = None
        if watts is None:
            await asyncio.sleep(args.poll)
            continue
        ingest(state, ts, watts, policy)
        log.info(
            "watts=%.0f pv=%s bat=%s peak=%.0f expected=%.0f regime=%s",
            watts,
            f"{panel_v:.1f}V" if panel_v is not None else "?",
            f"{battery_v:.1f}V" if battery_v is not None else "?",
            state.peak_w,
            state.expected_w,
            state.regime,
        )
        if should_pulse(state, ts, watts, policy, panel_v=panel_v, battery_v=battery_v):
            log.warning("%s — OFF %.1fs then ON", state.pulse_reason or f"watts={watts:.0f}", policy.off_s)
            if args.dry_run:
                msg = "dry-run skip"
                state.last_pulse_at = time.time()
            else:
                msg = await pulse(args.mac, policy.off_s)
                if "on=True" in msg:
                    state.last_pulse_at = time.time()
            state.below_since = None
            state.last_action = msg
            log.info("pulse done: %s", msg)
        await asyncio.sleep(args.poll)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    here = Path(__file__).resolve().parent.parent / "yield_config.json"
    p = argparse.ArgumentParser(description="Pulse Victron when local watts look stuck.")
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
