"""Sun-driven charger schedule: pure PV logic plus an enforcing asyncio loop.

There is no clock here. The charger follows the array:

- ON once panel voltage says the sun is up.
- OFF once output has collapsed *and* panel voltage says the sun is down. The
  conjunction matters: a full battery also reads ~0 W at noon, and that is not
  a sunset.

Both levels are inferred from this array's own history (``days`` table in the
watchdog store, see :class:`Learned`) instead of hardcoded volts and watts:

- ``wake_panel_v = pv_night + wake_frac × (pv_max − pv_night)`` over the last
  ``LEARN_DAYS`` days, so the level follows the season and the array itself.
- ``sleep_watts = sleep_frac × today's peak output``.

``wake_frac``/``sleep_frac`` are the only knobs. Until a day of history exists
the wake level falls back to ``BOOTSTRAP_VOC_BUS_MULT × bus`` (the "panel must
clear the bus by 2×" rule the pulse rules already use) and "sun down" falls back
to panel below the bus.

Sunrise clears a sunset latch, so a restart after dark cannot wedge the charger
out of a day, and a heavy cloud band that trips the sunset rule un-trips itself
when the sun returns. A sunset needs a panel reading: a missing one is not
evidence of anything, and if there is no reading at all before the first latch
the charger is left exactly as it is. Decisions latch per local day.

The controller reads before it writes, so a restart does not cost a GATT session
when the charger already matches the sun, and a BLE failure backs off instead of
hammering the adapter every poll. When the mode cannot be read back (this
SmartSolar does not always echo the mode register), it falls back to a blind
idempotent apply so the decision is still enforced.

The phone app still runs the older time-window schedule, so the two bridges
disagree while it is not being developed.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import logging
import math
import time
from dataclasses import dataclass
from typing import Awaitable, Callable

log = logging.getLogger("mppt_ble")

DEFAULT_ENABLED = True
DEFAULT_WAKE_FRAC = 0.5
DEFAULT_SLEEP_FRAC = 0.05
LEARN_DAYS = 7
LEARN_REFRESH_S = 60.0
BOOTSTRAP_VOC_BUS_MULT = 2.0


def _number(value: object, default: float | None = None) -> float | None:
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def _frac(value: object, default: float) -> float:
    number = _number(value)
    if number is None or not 0.0 <= number <= 1.0:
        return default
    return number


def _strict_frac(value: object, default: float, field: str) -> float:
    if value is None or value == "":
        return default
    number = _number(value)
    if number is None or not 0.0 <= number <= 1.0:
        raise ValueError(f"{field} must be a number between 0 and 1")
    return number


def coerce_enabled(value: object) -> bool:
    if isinstance(value, str):
        return value.strip().lower() not in ("", "0", "false", "no", "off", "disabled")
    return bool(value)


@dataclass
class Learned:
    """Levels inferred from stored history; every field is optional."""

    pv_max: float | None = None
    pv_night: float | None = None
    watts_peak_today: float | None = None
    days: int = 0

    @classmethod
    def from_mapping(cls, raw: object) -> "Learned":
        src = raw if isinstance(raw, dict) else {}
        days = _number(src.get("days"), 0.0) or 0.0
        return cls(
            pv_max=_number(src.get("pv_max")),
            pv_night=_number(src.get("pv_night")),
            watts_peak_today=_number(src.get("watts_peak_today")),
            days=int(days),
        )

    def wake_v(self, wake_frac: float) -> float | None:
        """Panel voltage half way up the array's own night-to-peak span."""
        if self.pv_max is None or self.pv_night is None or self.pv_max <= self.pv_night:
            return None
        return self.pv_night + wake_frac * (self.pv_max - self.pv_night)

    def sleep_w(self, sleep_frac: float) -> float | None:
        """Watts below which today's output counts as collapsed."""
        if not self.watts_peak_today or self.watts_peak_today <= 0:
            return None
        return sleep_frac * self.watts_peak_today


def sun_up(panel_v: float | None, bus_v: float | None, wake_v: float | None) -> bool:
    """Panel voltage high enough to call the sun up (bootstrap scales off the bus)."""
    if panel_v is None:
        return False
    if wake_v is not None:
        return panel_v >= wake_v
    if bus_v is None or bus_v <= 0:
        return False  # nothing to scale against yet: keep the charger off
    return panel_v >= BOOTSTRAP_VOC_BUS_MULT * bus_v


def sun_down(panel_v: float | None, bus_v: float | None, wake_v: float | None) -> bool:
    """Panel voltage low enough to call the sun down; unknown is never "down"."""
    if panel_v is None:
        return False
    if wake_v is not None:
        return panel_v < wake_v
    if bus_v is None or bus_v <= 0:
        return False
    return panel_v < bus_v


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
HistoryFn = Callable[[], dict]


def validated_config(
    enabled: object, wake_frac: object = None, sleep_frac: object = None
) -> dict:
    """Strict parse for user input; raises ValueError on a malformed fraction."""
    return {
        "enabled": coerce_enabled(enabled),
        "wake_frac": _strict_frac(wake_frac, DEFAULT_WAKE_FRAC, "wakeFrac"),
        "sleep_frac": _strict_frac(sleep_frac, DEFAULT_SLEEP_FRAC, "sleepFrac"),
    }


def normalize_config(raw: object) -> dict:
    """Lenient parse for stored config: bad/missing fields fall back to defaults."""
    src = raw if isinstance(raw, dict) else {}
    return {
        "enabled": coerce_enabled(src.get("enabled", DEFAULT_ENABLED)),
        "wake_frac": _frac(src.get("wake_frac", src.get("wakeFrac")), DEFAULT_WAKE_FRAC),
        "sleep_frac": _frac(
            src.get("sleep_frac", src.get("sleepFrac")), DEFAULT_SLEEP_FRAC
        ),
    }


class ScheduleController:
    """Applies the sun-driven decision through injected async read/apply callbacks.

    ``last_mode`` is the controller's belief about the device mode. It starts
    unknown; the first tick reads (or applies blind if the read fails). A manual
    on/off calls ``note_manual``, which parks the schedule until the sun changes
    its mind.
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
        watts: SampleFn | None = None,
        panel_v: SampleFn | None = None,
        bus_v: SampleFn | None = None,
        history: HistoryFn | None = None,
    ) -> None:
        self.config = normalize_config(config)
        self._read = read
        self._apply = apply
        self._tz = tz
        self._watts = watts
        self._panel_v = panel_v
        self._bus_v = bus_v
        self._history = history
        self.learned = Learned()
        self._learned_at = 0.0
        self._day: dt.date | None = None
        self._sunrise = False
        self._sunset = False
        self._poll_s = poll_s
        self._verify_s = verify_s
        self._retry_s = retry_s
        self._retry_max_s = retry_max_s
        self._now = now_fn
        self._wake = asyncio.Event()
        self.override_desired: bool | None = None
        self.last_reason = ""
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

    def _reset_day(self, local: dt.datetime) -> None:
        day = local.date()
        if day != self._day:
            self._day = day
            self._sunrise = False
            self._sunset = False

    def _learned_inputs(self) -> Learned:
        if self._history is None:
            return self.learned
        ts = self._now()
        if self._learned_at and ts - self._learned_at < LEARN_REFRESH_S:
            return self.learned
        self._learned_at = ts
        try:
            self.learned = Learned.from_mapping(self._history())
        except Exception:
            log.exception("schedule: learned levels failed")
        return self.learned

    def desired_on(self, now: float | None = None) -> bool | None:
        """True/False to enforce, or None when there is nothing to go on (or disabled)."""
        if not self.config["enabled"]:
            return None
        self._reset_day(self._local(now))
        panel_v = self._sample(self._panel_v)
        bus_v = self._sample(self._bus_v)
        watts = self._sample(self._watts)
        learned = self._learned_inputs()
        wake_v = learned.wake_v(self.config["wake_frac"])
        sleep_w = learned.sleep_w(self.config["sleep_frac"])
        panel_txt = "?" if panel_v is None else f"{panel_v:.0f}V"
        wake_txt = (
            f"{wake_v:.0f}V"
            if wake_v is not None
            else f"{BOOTSTRAP_VOC_BUS_MULT:g}× bus"
        )

        if sun_up(panel_v, bus_v, wake_v):
            if self._sunset:
                log.info("schedule: panel back up (%s) — clearing sunset latch", panel_txt)
            self._sunset = False
            if not self._sunrise:
                log.info("schedule: sun up: panel %s ≥ %s — charger ON", panel_txt, wake_txt)
            self._sunrise = True
            self.last_reason = f"sun up: panel {panel_txt} ≥ {wake_txt}"
            return True

        if panel_v is None and not self._sunrise and not self._sunset:
            # No evidence either way (BLE outage, or night with nothing reported): keep the
            # charger exactly as it is rather than inventing a decision.
            self.last_reason = "no panel reading yet"
            return None

        if sun_down(panel_v, bus_v, wake_v) and (
            watts is None or (sleep_w is not None and watts < sleep_w)
        ):
            # Missing output counts as collapsed: it is what a sleeping unit reports.
            counts = "no output" if watts is None else f"{watts:.0f}W < {sleep_w:.0f}W"
            if not self._sunset:
                log.info("schedule: sun down: panel %s, %s — charger OFF", panel_txt, counts)
            self._sunset = True
            self.last_reason = f"sun down: panel {panel_txt}, {counts}"
            return False

        if self._sunset:
            self.last_reason = "sunset latched"
            return False
        if self._sunrise:
            self.last_reason = f"sun up: panel {panel_txt}, waiting for output to collapse"
            return True
        self.last_reason = f"no sunrise yet: panel {panel_txt} < {wake_txt}"
        return False

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
        """Record a manual on/off and park the schedule until the sun changes its mind."""
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
        self.override_desired = self.desired_on()
        log.info(
            "schedule: manual %s overrides until the sun changes (%s)",
            "ON" if on else "OFF",
            self.last_reason,
        )

    def update_config(self, config: object) -> None:
        """Replace the sun rules, clear any manual override, and enforce now."""
        self.config = normalize_config(config)
        self.override_desired = None
        self._desired_memo = None
        self._day = None
        self._sunrise = False
        self._sunset = False
        self._learned_at = 0.0
        self._backoff = 0.0
        self._next_attempt_at = 0.0
        self.wake()

    def snapshot(self, now: float | None = None) -> dict:
        ts = self._now() if now is None else float(now)
        local = self._local(ts)
        desired = self.desired_on(ts)
        learned = self._learned_inputs()
        wake_v = learned.wake_v(self.config["wake_frac"])
        sleep_w = learned.sleep_w(self.config["sleep_frac"])
        return {
            "enabled": self.config["enabled"],
            "wakeFrac": self.config["wake_frac"],
            "sleepFrac": self.config["sleep_frac"],
            "desiredOn": desired,
            "sunUp": self._sunrise and not self._sunset,
            "sunriseLatched": self._sunrise,
            "sunsetLatched": self._sunset,
            "reason": self.last_reason,
            "learned": {
                "days": learned.days,
                "pvMax": learned.pv_max,
                "pvNight": learned.pv_night,
                "wattsPeakToday": learned.watts_peak_today,
                "wakeV": wake_v,
                "sleepW": sleep_w,
                "bootstrap": wake_v is None,
            },
            "panelV": self._sample(self._panel_v),
            "busV": self._sample(self._bus_v),
            "watts": self._sample(self._watts),
            "override": self.override_desired is not None,
            "overrideDesired": self.override_desired,
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
            self.override_desired = None
            return
        desired = self.desired_on(ts)
        if desired is None:
            return  # no opinion: leave the charger alone
        if self.override_desired is not None:
            if desired == self.override_desired:
                return  # the manual tap still matches the sun: leave the charger alone
            log.info("schedule: sun changed its mind (%s)", self.last_reason)
            self.override_desired = None
        if desired != self._desired_memo:
            # sun flipped or config changed: try immediately, even after a failure
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
                # A failed verify is not evidence the charger is on, so drop the belief and let the
                # blind apply below re-assert the decision; otherwise one unreadable register keeps
                # the charger off for the rest of the day.
                self.last_error = f"verify: {result.message}"
                log.warning("schedule: verify: %s — mode unknown, re-applying", result.message)
                self.last_mode = None
            # else: mode unknown and the read failed. This unit does not always
            # echo the mode register, so fall through to a blind (idempotent)
            # apply instead of leaving the decision unenforced.

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
                "schedule: charger %s (%s)",
                "ON" if desired else "OFF",
                self.last_reason,
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
