"""Daily charger window: pure logic plus an enforcing asyncio loop.

Mirrors ``app/.../charger/ChargerSchedule.kt`` so both bridges agree:

- ``enable < disable`` -> ON inside ``[enable, disable)`` (daytime window).
- ``enable > disable`` -> overnight window, ON from ``enable`` until ``disable``.
- ``enable == disable`` -> 24 h window (a degenerate config never forces OFF).
- A manual on/off pauses the window until the next boundary; the schedule then
  re-asserts itself.

The controller reads before it writes, so a restart does not cost a GATT
session when the charger already matches the window, and a BLE failure backs
off instead of hammering the adapter every poll. When the mode cannot be read
back (this SmartSolar does not always echo the mode register), it falls back
to a blind idempotent apply so the window is still enforced.

Optional PV gating (:class:`PvSchedule`) expands the daytime window: once
``wake_after`` has passed and panel voltage reaches ``wake_panel_v`` the
charger turns ON early, and once ``sleep_after`` has passed and output falls
below ``sleep_watts`` it turns OFF early. Both decisions latch for the local
day, and the base window stays the fallback, so the charger is never left off
because of a missing/odd BLE reading. PV gating is ignored for overnight or
24 h windows where the base window is already the intent.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import logging
import math
import re
import time
from dataclasses import dataclass
from typing import Awaitable, Callable

log = logging.getLogger("mppt_ble")

DEFAULT_ENABLED = True
DEFAULT_ENABLE = "07:00"
DEFAULT_DISABLE = "18:00"
DEFAULT_PV_ENABLED = True
DEFAULT_PV_WAKE_AFTER = "05:00"
DEFAULT_PV_WAKE_PANEL_V = 60.0
DEFAULT_PV_SLEEP_AFTER = "17:00"
DEFAULT_PV_SLEEP_WATTS = 40.0


def _number(value: object, default: float) -> float:
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


@dataclass
class PvSchedule:
    """PV-based early wake / early sleep layered on top of the daily window.

    ``wake_after``/``sleep_after`` are minutes since local midnight. A reading
    of ``None`` (stale Instant Readout, night-NA panel voltage) never latches.
    """

    enabled: bool = True
    wake_after: int = 5 * 60
    wake_panel_v: float = 60.0
    sleep_after: int = 17 * 60
    sleep_watts: float = 40.0

    @classmethod
    def from_mapping(cls, raw: object) -> "PvSchedule":
        src = raw if isinstance(raw, dict) else {}
        return cls(
            enabled=coerce_enabled(src.get("enabled", True)),
            wake_after=_minutes_or(src.get("wake_after", src.get("wakeAfter")), 5 * 60),
            wake_panel_v=_number(src.get("wake_panel_v", src.get("wakePanelV")), 60.0),
            sleep_after=_minutes_or(src.get("sleep_after", src.get("sleepAfter")), 17 * 60),
            sleep_watts=_number(src.get("sleep_watts", src.get("sleepWatts")), 40.0),
        )


_HHMM = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")


@dataclass
class ModeResult:
    """BLE read/apply outcome, decoupled from ``client.SessionResult``."""

    success: bool
    on: bool | None
    message: str
    mode: int | None = None


ReadFn = Callable[[], Awaitable[ModeResult]]
ApplyFn = Callable[[bool], Awaitable[ModeResult]]
SampleFn = Callable[[], float | None]


def parse_hhmm(value: object) -> int | None:
    """``"HH:mm"`` (also ``"H:mm"``) -> minutes since midnight, else None."""
    if value is None:
        return None
    match = _HHMM.match(str(value).strip())
    if not match:
        return None
    return int(match.group(1)) * 60 + int(match.group(2))


def format_minutes(minutes: int) -> str:
    clamped = ((int(minutes) % 1440) + 1440) % 1440
    return f"{clamped // 60:02d}:{clamped % 60:02d}"


def _minutes_or(value: object, default: int) -> int:
    parsed = parse_hhmm(value)
    return default if parsed is None else parsed


def pv_wake_ready(now_minutes: int, panel_v: float | None, pv: PvSchedule) -> bool:
    """True once past ``wake_after`` and panel voltage is high enough."""
    return (
        pv.enabled
        and now_minutes >= pv.wake_after
        and panel_v is not None
        and panel_v >= pv.wake_panel_v
    )


def pv_sleep_due(now_minutes: int, watts: float | None, pv: PvSchedule) -> bool:
    """True when it is past ``sleep_after`` and PV output has collapsed."""
    return (
        pv.enabled
        and now_minutes >= pv.sleep_after
        and watts is not None
        and watts < pv.sleep_watts
    )


def coerce_enabled(value: object) -> bool:
    if isinstance(value, str):
        return value.strip().lower() not in ("", "0", "false", "no", "off", "disabled")
    return bool(value)


def is_in_window(now_minutes: int, enable_minutes: int, disable_minutes: int) -> bool:
    """True when the charger should be ON at the given minute-of-day."""
    if enable_minutes == disable_minutes:
        return True  # degenerate: 24 h window
    if enable_minutes < disable_minutes:
        return enable_minutes <= now_minutes < disable_minutes
    return now_minutes >= enable_minutes or now_minutes < disable_minutes  # overnight


def next_transition(
    now_minutes: int, enable_minutes: int, disable_minutes: int
) -> int | None:
    """Minute-of-day of the next boundary strictly after now, or None (24 h)."""
    if enable_minutes == disable_minutes:
        return None
    candidates = [m for m in (enable_minutes, disable_minutes) if m > now_minutes]
    if candidates:
        return min(candidates)
    return min(enable_minutes, disable_minutes)  # both passed: first boundary tomorrow


def next_transition_at(
    now: dt.datetime, enable_minutes: int, disable_minutes: int
) -> dt.datetime | None:
    """Local datetime of the next boundary after ``now``, or None (24 h)."""
    minutes = next_transition(now.hour * 60 + now.minute, enable_minutes, disable_minutes)
    if minutes is None:
        return None
    boundary = now.replace(hour=minutes // 60, minute=minutes % 60, second=0, microsecond=0)
    if boundary <= now:
        boundary += dt.timedelta(days=1)
    return boundary


def validated_config(
    enabled: object, enable_time: object, disable_time: object, pv: object = None
) -> dict:
    """Strict parse for user input; raises ValueError on a malformed time."""
    enable_minutes = parse_hhmm(enable_time)
    if enable_minutes is None:
        raise ValueError("enableTime must be HH:MM")
    disable_minutes = parse_hhmm(disable_time)
    if disable_minutes is None:
        raise ValueError("disableTime must be HH:MM")
    return {
        "enabled": coerce_enabled(enabled),
        "enable_time": format_minutes(enable_minutes),
        "disable_time": format_minutes(disable_minutes),
        "pv": validated_pv_config(pv),
    }


def normalize_config(raw: object) -> dict:
    """Lenient parse for stored config: bad/missing fields fall back to defaults."""
    src = raw if isinstance(raw, dict) else {}
    enabled = coerce_enabled(src.get("enabled", DEFAULT_ENABLED))
    enable_minutes = parse_hhmm(src.get("enable_time", src.get("enableTime")))
    disable_minutes = parse_hhmm(src.get("disable_time", src.get("disableTime")))
    if enable_minutes is None:
        enable_minutes = parse_hhmm(DEFAULT_ENABLE)
    if disable_minutes is None:
        disable_minutes = parse_hhmm(DEFAULT_DISABLE)
    return {
        "enabled": enabled,
        "enable_time": format_minutes(enable_minutes),
        "disable_time": format_minutes(disable_minutes),
        "pv": normalize_pv_config(src.get("pv")),
    }


def normalize_pv_config(raw: object) -> dict:
    """Lenient parse for the PV wake/sleep rules; bad fields fall back."""
    src = raw if isinstance(raw, dict) else {}
    wake = parse_hhmm(src.get("wake_after", src.get("wakeAfter")))
    if wake is None:
        wake = parse_hhmm(DEFAULT_PV_WAKE_AFTER)
    sleep = parse_hhmm(src.get("sleep_after", src.get("sleepAfter")))
    if sleep is None:
        sleep = parse_hhmm(DEFAULT_PV_SLEEP_AFTER)
    return {
        "enabled": coerce_enabled(src.get("enabled", DEFAULT_PV_ENABLED)),
        "wake_after": format_minutes(wake),
        "wake_panel_v": max(
            0.0,
            _number(
                src.get("wake_panel_v", src.get("wakePanelV")), DEFAULT_PV_WAKE_PANEL_V
            ),
        ),
        "sleep_after": format_minutes(sleep),
        "sleep_watts": max(
            0.0,
            _number(
                src.get("sleep_watts", src.get("sleepWatts")), DEFAULT_PV_SLEEP_WATTS
            ),
        ),
    }


def validated_pv_config(raw: object) -> dict:
    """Strict parse for user input; raises ValueError on a malformed time."""
    src = raw if isinstance(raw, dict) else {}
    wake_raw = src.get("wake_after", src.get("wakeAfter"))
    wake = parse_hhmm(wake_raw)
    if wake is None:
        if wake_raw is not None:
            raise ValueError("pv wakeAfter must be HH:MM")
        wake = parse_hhmm(DEFAULT_PV_WAKE_AFTER)
    sleep_raw = src.get("sleep_after", src.get("sleepAfter"))
    sleep = parse_hhmm(sleep_raw)
    if sleep is None:
        if sleep_raw is not None:
            raise ValueError("pv sleepAfter must be HH:MM")
        sleep = parse_hhmm(DEFAULT_PV_SLEEP_AFTER)
    return {
        "enabled": coerce_enabled(src.get("enabled", DEFAULT_PV_ENABLED)),
        "wake_after": format_minutes(wake),
        "wake_panel_v": max(
            0.0,
            _number(
                src.get("wake_panel_v", src.get("wakePanelV")), DEFAULT_PV_WAKE_PANEL_V
            ),
        ),
        "sleep_after": format_minutes(sleep),
        "sleep_watts": max(
            0.0,
            _number(
                src.get("sleep_watts", src.get("sleepWatts")), DEFAULT_PV_SLEEP_WATTS
            ),
        ),
    }


class ScheduleController:
    """Applies the daily window through injected async read/apply callbacks.

    ``last_mode`` is the controller's belief about the device mode. It starts
    unknown; the first tick reads (or applies blind if the read fails). Manual
    on/off calls ``note_manual`` so the window pauses until the next boundary.
    """

    def __init__(
        self,
        config: object,
        read: ReadFn,
        apply: ApplyFn,
        *,
        tz: dt.tzinfo | None = None,
        poll_s: float = 20.0,
        verify_s: float = 600.0,
        retry_s: float = 120.0,
        retry_max_s: float = 900.0,
        now_fn: Callable[[], float] = time.time,
        pv: PvSchedule | object | None = None,
        watts: SampleFn | None = None,
        panel_v: SampleFn | None = None,
    ) -> None:
        self.config = normalize_config(config)
        self._read = read
        self._apply = apply
        self._tz = tz
        if pv is not None:
            self.pv = pv if isinstance(pv, PvSchedule) else PvSchedule.from_mapping(pv)
        else:
            self.pv = PvSchedule.from_mapping(self.config["pv"])
        self._watts = watts
        self._panel_v = panel_v
        self._pv_day: dt.date | None = None
        self._morning_started = False
        self._evening_ended = False
        self._poll_s = poll_s
        self._verify_s = verify_s
        self._retry_s = retry_s
        self._retry_max_s = retry_max_s
        self._now = now_fn
        self._wake = asyncio.Event()
        self.override_until = 0.0
        self.last_mode: bool | None = None
        self.last_applied_at = 0.0
        self.last_applied_on: bool | None = None
        self.last_checked_at = 0.0
        self.last_error: str | None = None
        self._last_verify_at = 0.0
        self._next_attempt_at = 0.0
        self._backoff = 0.0
        self._desired_memo: bool | None = None

    # -- helpers ---------------------------------------------------------

    def _local(self, ts: float | None = None) -> dt.datetime:
        ts = self._now() if ts is None else float(ts)
        if self._tz is not None:
            return dt.datetime.fromtimestamp(ts, self._tz)
        return dt.datetime.fromtimestamp(ts).astimezone()

    def _minutes(self) -> tuple[int, int]:
        enable = parse_hhmm(self.config["enable_time"])
        disable = parse_hhmm(self.config["disable_time"])
        return (
            enable if enable is not None else parse_hhmm(DEFAULT_ENABLE),
            disable if disable is not None else parse_hhmm(DEFAULT_DISABLE),
        )

    def _sample(self, fn: SampleFn | None) -> float | None:
        if fn is None:
            return None
        try:
            value = fn()
        except Exception:
            log.exception("schedule: PV sample failed")
            return None
        if isinstance(value, bool):
            return None
        return float(value) if isinstance(value, (int, float)) else None

    def _reset_pv_day(self, local: dt.datetime) -> None:
        day = local.date()
        if day != self._pv_day:
            self._pv_day = day
            self._morning_started = False
            self._evening_ended = False

    def desired_on(self, now: float | None = None) -> bool | None:
        if not self.config["enabled"]:
            return None
        local = self._local(now)
        now_minutes = local.hour * 60 + local.minute
        enable, disable = self._minutes()
        base = is_in_window(now_minutes, enable, disable)
        pv = self.pv
        # PV gating only makes sense for a daytime window; an overnight / 24 h
        # window is an explicit intent that should not be trimmed by sunshine.
        if pv is None or not pv.enabled or enable >= disable:
            return base
        self._reset_pv_day(local)
        if not self._evening_ended:
            if pv_sleep_due(now_minutes, self._sample(self._watts), pv):
                self._evening_ended = True
                log.info(
                    "schedule: PV sleep after %s (watts < %.0fW)",
                    format_minutes(pv.sleep_after),
                    pv.sleep_watts,
                )
        if not self._evening_ended and not self._morning_started and not base:
            if pv_wake_ready(now_minutes, self._sample(self._panel_v), pv):
                self._morning_started = True
                log.info(
                    "schedule: PV wake after %s (panel ≥ %.1fV)",
                    format_minutes(pv.wake_after),
                    pv.wake_panel_v,
                )
        if self._evening_ended:
            return False
        if self._morning_started:
            return True
        return base

    def _fail(self, message: str) -> None:
        self.last_error = message or "schedule apply failed"
        self._backoff = (
            self._retry_s if self._backoff <= 0 else min(self._retry_max_s, self._backoff * 2)
        )
        self._next_attempt_at = self._now() + self._backoff
        log.warning("schedule: %s (retry in %ds)", self.last_error, int(self._backoff))

    # -- public API ------------------------------------------------------

    def wake(self) -> None:
        self._wake.set()

    def note_manual(self, on: bool | None) -> None:
        """Record a manual on/off and pause the window until the next boundary."""
        if on is None:
            return
        self.last_mode = bool(on)
        self.last_applied_at = self._now()
        self.last_applied_on = bool(on)
        self.last_error = None
        self._backoff = 0.0
        self._next_attempt_at = 0.0
        if not self.config["enabled"]:
            return
        nxt = next_transition_at(self._local(), *self._minutes())
        if nxt is not None:
            self.override_until = nxt.timestamp()
            log.info(
                "schedule: manual %s overrides window until %s",
                "ON" if on else "OFF",
                nxt.strftime("%H:%M"),
            )

    def update_config(self, config: object) -> None:
        """Replace the window, clear any manual override, and enforce now."""
        self.config = normalize_config(config)
        self.pv = PvSchedule.from_mapping(self.config["pv"])
        self.override_until = 0.0
        self._desired_memo = None
        self._pv_day = None
        self._morning_started = False
        self._evening_ended = False
        self._backoff = 0.0
        self._next_attempt_at = 0.0
        self.wake()

    def snapshot(self, now: float | None = None) -> dict:
        ts = self._now() if now is None else float(now)
        local = self._local(ts)
        enable, disable = self._minutes()
        desired = self.desired_on(ts)
        nxt = next_transition_at(local, enable, disable) if desired is not None else None
        override_until = self.override_until if self.override_until > ts else 0.0
        override_time = None
        if override_until:
            override_time = self._local(override_until).strftime("%H:%M")
        pv_snap = None
        if self.pv is not None and self.pv.enabled:
            pv_snap = {
                "enabled": True,
                "wakeAfter": format_minutes(self.pv.wake_after),
                "wakePanelV": self.pv.wake_panel_v,
                "sleepAfter": format_minutes(self.pv.sleep_after),
                "sleepWatts": self.pv.sleep_watts,
                "morningStarted": self._morning_started,
                "eveningEnded": self._evening_ended,
                "watts": self._sample(self._watts),
                "panelV": self._sample(self._panel_v),
            }
        return {
            "enabled": self.config["enabled"],
            "enableTime": self.config["enable_time"],
            "disableTime": self.config["disable_time"],
            "inWindow": desired,
            "pv": pv_snap,
            "desiredOn": desired,
            "nextTransitionAt": int(nxt.timestamp()) if nxt else None,
            "nextTransitionTime": nxt.strftime("%H:%M") if nxt else None,
            "nextTransitionOn": (not desired) if (nxt and desired is not None) else None,
            "overrideUntil": int(override_until) if override_until else None,
            "overrideUntilTime": override_time,
            "lastMode": self.last_mode,
            "lastAppliedOn": self.last_applied_on,
            "lastAppliedAt": int(self.last_applied_at) if self.last_applied_at else None,
            "lastError": self.last_error,
            "serverTime": local.strftime("%H:%M"),
            "serverZone": local.tzname() or "",
        }

    # -- enforcement -----------------------------------------------------

    async def tick(self, now: float | None = None) -> None:
        """One enforcement pass; safe to call directly in tests."""
        ts = self._now() if now is None else float(now)
        self.last_checked_at = ts
        if not self.config["enabled"]:
            self.override_until = 0.0
            return
        if self.override_until:
            if ts < self.override_until:
                return
            self.override_until = 0.0
            log.info("schedule: manual override ended; re-asserting window")
        desired = self.desired_on(ts)
        assert desired is not None
        if desired != self._desired_memo:
            # boundary/config change: try immediately, even after a failure
            self._desired_memo = desired
            self._backoff = 0.0
            self._next_attempt_at = 0.0
        if ts < self._next_attempt_at:
            return
        verify_due = (
            self.last_mode is not None and (ts - self._last_verify_at) >= self._verify_s
        )

        if self.last_mode is None or (verify_due and self.last_mode == desired):
            result = await self._read()
            self._last_verify_at = ts
            if result.success and result.on is not None:
                self.last_mode = result.on
            elif self.last_mode is not None:
                self._fail(f"verify: {result.message}")
                return
            # else: mode unknown and the read failed. This unit does not always
            # echo the mode register, so fall through to a blind (idempotent)
            # apply instead of leaving the window unenforced.

        if self.last_mode == desired:
            self.last_error = None
            return

        result = await self._apply(desired)
        if result.success:
            self.last_mode = result.on if result.on is not None else desired
            self.last_applied_at = ts
            self.last_applied_on = desired
            self.last_error = None
            self._backoff = 0.0
            self._next_attempt_at = 0.0
            log.info(
                "schedule: charger %s (window %s-%s)",
                "ON" if desired else "OFF",
                self.config["enable_time"],
                self.config["disable_time"],
            )
        else:
            self._fail(result.message or "apply failed")

    async def run(self) -> None:
        await asyncio.sleep(2.0)
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("schedule tick failed")
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=self._poll_s)
            except asyncio.TimeoutError:
                pass
            self._wake.clear()
