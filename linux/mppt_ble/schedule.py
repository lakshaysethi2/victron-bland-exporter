"""Automatic daily charger ON/OFF window for ``mppt_ble serve`` (issue #67).

The remote ``/charger`` page could already flip the charger by hand, but nothing
turned it on in the morning and off in the evening on its own. This module is
that missing half: a user-set daily window, e.g. ON 06:45 / OFF 17:30.

Pure logic plus a little JSON persistence — no BLE, no aiohttp, no asyncio — so
the window semantics are unit-testable on any host (``pytest test_schedule.py``).
The serve loop in ``__main__`` only has to ask [due_action] and push the answer.

Clock: wall-clock ``HH:mm`` in the host's local timezone (the box beside the
MPPT). Same convention as the Android app's daily window, and the semantics
deliberately mirror ``app/.../charger/ChargerSchedule.kt`` so both halves of
this repo agree:

  - on 06:45 / off 17:30 -> charger ON while the clock is inside [06:45, 17:30)
  - on 17:30 / off 06:45 -> overnight window, ON from 17:30 until 06:45
  - on == off            -> 24 h window (always ON), so a degenerate config
                            never locks the charger off
  - a manual ON/OFF pauses the window until the next boundary; the boundary
    after that hands control back automatically ("resume schedule")

Configuration precedence, lowest to highest:
  built-in defaults < ``schedule`` object in ``~/.config/mppt/devices.json``
  < environment (``MPPT_SCHEDULE``, ``MPPT_SCHEDULE_ON``, ``MPPT_SCHEDULE_OFF``,
  ``MPPT_SCHEDULE_WINDOW``) < ``POST /charger/schedule`` (which writes the file).

Nothing here may contain site-identifying values — times and booleans only.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from pathlib import Path

from .config import DEFAULT_PATH

log = logging.getLogger("mppt_ble")

#: Issue #67's worked example: enable in the early morning, disable late afternoon.
DEFAULT_ON = "06:45"
DEFAULT_OFF = "17:30"

#: Same two values as minutes since midnight (kept literal so they can be used
#: before [parse_minutes] exists; ``test_schedule.py`` pins them to the strings).
DEFAULT_ON_MINUTES = 6 * 60 + 45
DEFAULT_OFF_MINUTES = 17 * 60 + 30

#: How often the serve loop looks at the clock. Cheap: no BLE, no I/O.
CHECK_S = 15

#: Grace period after start, so serve can bring up scanning and the HTTP socket
#: before the first GATT write is attempted.
START_DELAY_S = 3.0

#: Re-assert the window at most this often when something else moved the charger
#: (a hand flip, a lost write, a service restart). Same 10 minutes as the app.
REAPPLY_S = 600

_TIME_RE = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
_WINDOW_RE = re.compile(r"^\s*([01]?\d|2[0-3]):([0-5]\d)\s*-\s*([01]?\d|2[0-3]):([0-5]\d)\s*$")

_TRUE = {"1", "on", "true", "yes", "y", "enable", "enabled"}
_FALSE = {"0", "off", "false", "no", "n", "disable", "disabled"}


def coerce_flag(value: object) -> bool | None:
    """``true``/``"on"``/``1`` -> True, ``false``/``"off"``/``0`` -> False.

    ``None`` means "not a flag I recognise". Callers must not guess: turning the
    window off because someone typed ``"sometimes"`` would silently stop the
    automation, which is the failure mode this module exists to avoid.
    """
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        text = value.strip().lower()
        if text in _TRUE:
            return True
        if text in _FALSE:
            return False
    return None


# --------------------------------------------------------------------------- #
# time helpers
# --------------------------------------------------------------------------- #


def parse_minutes(hhmm: object) -> int | None:
    """``"06:45"`` -> 405 minutes since midnight; ``None`` when malformed.

    Accepts a single-digit hour (``"6:45"``) because that is what people type.
    """
    if not isinstance(hhmm, str):
        return None
    m = _TIME_RE.match(hhmm.strip())
    if not m:
        return None
    return int(m.group(1)) * 60 + int(m.group(2))


def format_minutes(minutes: int) -> str:
    """405 -> ``"06:45"`` (wraps into a single day)."""
    clamped = int(minutes) % 1440
    return "%02d:%02d" % (clamped // 60, clamped % 60)


def is_valid_time(hhmm: object) -> bool:
    return parse_minutes(hhmm) is not None


def parse_window(text: object) -> tuple[str, str] | None:
    """``"06:45-17:30"`` -> ``("06:45", "17:30")``; ``None`` when malformed."""
    if not isinstance(text, str):
        return None
    m = _WINDOW_RE.match(text)
    if not m:
        return None
    on = "%02d:%02d" % (int(m.group(1)), int(m.group(2)))
    off = "%02d:%02d" % (int(m.group(3)), int(m.group(4)))
    return on, off


def human_time(hhmm: str) -> str:
    """``"06:45"`` -> ``"6:45 AM"`` — the page reads better in 12-hour form."""
    minutes = parse_minutes(hhmm)
    if minutes is None:
        return str(hhmm)
    hour, minute = divmod(minutes, 60)
    suffix = "AM" if hour < 12 else "PM"
    display = hour % 12
    if display == 0:
        display = 12
    return "%d:%02d %s" % (display, minute, suffix)


def countdown(seconds: float) -> str:
    """``41520`` -> ``"in 11h 32m"`` (never negative, never finer than a minute)."""
    s = max(0, int(seconds))
    if s < 60:
        return "in under a minute"
    minutes, _ = divmod(s, 60)
    hours, mins = divmod(minutes, 60)
    if hours and mins:
        return "in %dh %dm" % (hours, mins)
    if hours:
        return "in %dh" % hours
    return "in %dm" % mins


def local_minutes(ts: float, tz=None) -> int:
    """Minutes since midnight for ``ts`` in ``tz`` (default: the host clock)."""
    dt = datetime.fromtimestamp(ts, tz) if tz is not None else datetime.fromtimestamp(ts)
    return dt.hour * 60 + dt.minute


def local_zone_name(ts: float, tz=None) -> str:
    """Timezone label shown next to the window, so "06:45" is never ambiguous."""
    dt = datetime.fromtimestamp(ts, tz) if tz is not None else datetime.fromtimestamp(ts).astimezone()
    name = getattr(dt.tzinfo, "key", None) or dt.tzname() or ""
    if name:
        return str(name)
    offset = dt.utcoffset() or timedelta(0)
    total = int(offset.total_seconds() // 60)
    return "UTC%+03d:%02d" % (total // 60, abs(total) % 60)


# --------------------------------------------------------------------------- #
# window semantics
# --------------------------------------------------------------------------- #


def in_window(now_minutes: int, on_minutes: int, off_minutes: int) -> bool:
    """True when the charger should be ON at ``now_minutes``."""
    if on_minutes == off_minutes:
        return True  # degenerate: treat as a 24 h window, never lock the charger off
    if on_minutes < off_minutes:
        return on_minutes <= now_minutes < off_minutes
    return now_minutes >= on_minutes or now_minutes < off_minutes  # overnight


def next_transition(now_minutes: int, on_minutes: int, off_minutes: int) -> int:
    """The next boundary strictly after ``now_minutes`` (wraps to tomorrow)."""
    later = [m for m in (on_minutes, off_minutes) if m > now_minutes]
    if later:
        return min(later)
    return min(on_minutes, off_minutes)


def next_transition_ts(ts: float, on_minutes: int, off_minutes: int, tz=None) -> float:
    """Epoch seconds of the next boundary after ``ts``.

    Wall-clock arithmetic, so a DST shift moves the flip by the shift (the
    window is "06:45 local", not "06:45 UTC").
    """
    dt = datetime.fromtimestamp(ts, tz) if tz is not None else datetime.fromtimestamp(ts).astimezone()
    now_m = dt.hour * 60 + dt.minute
    target = next_transition(now_m, on_minutes, off_minutes)
    candidate = dt.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(minutes=target)
    if candidate <= dt:
        candidate += timedelta(days=1)
    return candidate.timestamp()


# --------------------------------------------------------------------------- #
# configuration
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Schedule:
    """The daily window plus any manual override currently pausing it."""

    enabled: bool = True
    on_time: str = DEFAULT_ON
    off_time: str = DEFAULT_OFF
    #: Epoch seconds until which a hand ON/OFF pauses the window; 0 = none.
    override_until: float = 0.0
    #: Where the effective values came from: default | file | env | http.
    source: str = "default"

    @property
    def on_minutes(self) -> int:
        """Minutes since midnight, falling back to the default when malformed."""
        parsed = parse_minutes(self.on_time)
        return DEFAULT_ON_MINUTES if parsed is None else parsed

    @property
    def off_minutes(self) -> int:
        parsed = parse_minutes(self.off_time)
        return DEFAULT_OFF_MINUTES if parsed is None else parsed

    def wants_on(self, ts: float, tz=None) -> bool:
        """What the window wants at ``ts`` (ignores ``enabled`` and overrides)."""
        return in_window(local_minutes(ts, tz), self.on_minutes, self.off_minutes)

    def is_overridden(self, ts: float) -> bool:
        return self.override_until > ts

    def next_change_ts(self, ts: float, tz=None) -> float:
        return next_transition_ts(ts, self.on_minutes, self.off_minutes, tz)

    def to_dict(self) -> dict:
        """File/JSON shape. ``override_until`` is stored so a restart of the
        service does not silently resume the window mid-override."""
        return {
            "enabled": bool(self.enabled),
            "on": self.on_time,
            "off": self.off_time,
            "override_until": int(self.override_until) if self.override_until else 0,
        }

    def describe(self, ts: float, tz=None) -> str:
        """One plain-language line for the page and the log."""
        if not self.enabled:
            return "Automatic ON/OFF is off — the charger only changes when you tap Enable or Disable."
        parts = [
            "Turns ON at %s and OFF at %s daily (%s)."
            % (human_time(self.on_time), human_time(self.off_time), local_zone_name(ts, tz))
        ]
        if self.is_overridden(ts):
            when = datetime.fromtimestamp(self.override_until, tz) if tz is not None else datetime.fromtimestamp(self.override_until)
            parts.append(
                "Paused by a manual ON/OFF until %s, then the window resumes by itself."
                % human_time(when.strftime("%H:%M"))
            )
        else:
            wants = "ON" if self.wants_on(ts, tz) else "OFF"
            nxt = self.next_change_ts(ts, tz)
            edge = "ON" if wants == "OFF" else "OFF"
            parts.append(
                "Wants %s now; next change is %s at %s (%s)."
                % (
                    wants,
                    edge,
                    human_time(format_minutes(local_minutes(nxt, tz))),
                    countdown(nxt - ts),
                )
            )
        return " ".join(parts)


@dataclass
class ScheduleState:
    """What the serve loop has already pushed, so it does not re-flip every tick."""

    applied: bool | None = None
    last_attempt: float = 0.0
    last_ok: bool = False
    last_message: str = ""
    last_action_at: float = 0.0
    last_action: str = ""


def _valid_or_default(value: object, default: str, label: str) -> str:
    if value is None:
        return default
    text = str(value).strip()
    if not text:
        return default
    if parse_minutes(text) is None:
        log.warning("schedule: ignoring invalid %s %r (want HH:MM)", label, value)
        return default
    return format_minutes(parse_minutes(text) or 0)


def schedule_from_mapping(data: object, source: str) -> Schedule:
    """Build a [Schedule] from a ``{"enabled":…,"on":…,"off":…}`` mapping."""
    if not isinstance(data, dict):
        return Schedule(source=source)
    enabled = coerce_flag(data.get("enabled"))
    if data.get("enabled") is not None and enabled is None:
        log.warning("schedule: ignoring unrecognised enabled=%r (want true|false)", data.get("enabled"))
    return Schedule(
        enabled=True if enabled is None else enabled,
        on_time=_valid_or_default(data.get("on"), DEFAULT_ON, "on time"),
        off_time=_valid_or_default(data.get("off"), DEFAULT_OFF, "off time"),
        source=source,
    )


def schedule_from_env(env: dict | None = None, base: Schedule | None = None) -> Schedule:
    """Apply ``MPPT_SCHEDULE*`` on top of [base].

    ``MPPT_SCHEDULE=on|off`` toggles enforcement, ``MPPT_SCHEDULE_ON`` /
    ``MPPT_SCHEDULE_OFF`` set one edge, ``MPPT_SCHEDULE_WINDOW=06:45-17:30``
    sets both. Env wins over the file so a systemd unit can pin the window
    without anyone editing JSON on the host.
    """
    env = os.environ if env is None else env
    current = base or Schedule()
    enabled = current.enabled
    on_time = current.on_time
    off_time = current.off_time
    changed = False

    flag = str(env.get("MPPT_SCHEDULE", "") or "").strip().lower()
    if flag:
        if flag in _TRUE:
            enabled, changed = True, True
        elif flag in _FALSE:
            enabled, changed = False, True
        else:
            window = parse_window(flag)
            if window:
                on_time, off_time, changed = window[0], window[1], True
            else:
                log.warning("schedule: ignoring MPPT_SCHEDULE=%r (want on|off|HH:MM-HH:MM)", flag)

    window = parse_window(env.get("MPPT_SCHEDULE_WINDOW"))
    if window:
        on_time, off_time, changed = window[0], window[1], True

    env_on = str(env.get("MPPT_SCHEDULE_ON", "") or "").strip()
    if env_on:
        on_time = _valid_or_default(env_on, on_time, "MPPT_SCHEDULE_ON")
        changed = True
    env_off = str(env.get("MPPT_SCHEDULE_OFF", "") or "").strip()
    if env_off:
        off_time = _valid_or_default(env_off, off_time, "MPPT_SCHEDULE_OFF")
        changed = True

    if not changed:
        return current
    return replace(current, enabled=enabled, on_time=on_time, off_time=off_time, source="env")


def load_schedule(cfg: dict | None = None, env: dict | None = None, path: Path = DEFAULT_PATH, ts: float | None = None) -> Schedule:
    """Defaults -> ``devices.json`` ``schedule`` -> environment.

    ``cfg`` is an already-loaded devices mapping (what ``config.load_devices``
    returns); pass ``None`` to read [path] here. A stored override that has
    already expired is dropped so a restart does not pause the window forever.
    """
    now = time.time() if ts is None else float(ts)
    data = cfg
    if data is None:
        data = {}
        try:
            if Path(path).is_file():
                data = json.loads(Path(path).read_text())
        except Exception:
            log.exception("schedule: could not read %s", path)
            data = {}
    stored = (data or {}).get("schedule") if isinstance(data, dict) else None
    schedule = schedule_from_mapping(stored, "file") if stored else Schedule(source="default")

    override = 0.0
    if isinstance(stored, dict):
        try:
            override = float(stored.get("override_until") or 0.0)
        except (TypeError, ValueError):
            override = 0.0
    if override and override <= now:
        override = 0.0
    if override:
        schedule = replace(schedule, override_until=override)

    return schedule_from_env(env, schedule)


def save_schedule(schedule: Schedule, path: Path = DEFAULT_PATH) -> Schedule:
    """Persist the window into ``devices.json`` without touching mac/keys.

    The write is best-effort: an unwritable config dir must not stop the window
    running in this process, so the caller keeps using the returned object.
    """
    clean = replace(schedule, source="http")
    path = Path(path)
    try:
        data: dict = {}
        if path.is_file():
            loaded = json.loads(path.read_text())
            if isinstance(loaded, dict):
                data = loaded
        data["schedule"] = clean.to_dict()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
        tmp.replace(path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    except Exception:
        log.exception("schedule: could not persist %s (window still active in memory)", path)
    return clean


def with_override(schedule: Schedule, ts: float, tz=None) -> Schedule:
    """Pause the window until its next boundary (a hand ON/OFF just happened)."""
    return replace(schedule, override_until=schedule.next_change_ts(ts, tz))


def resume(schedule: Schedule) -> Schedule:
    """Hand control back to the window immediately."""
    return replace(schedule, override_until=0.0)


# --------------------------------------------------------------------------- #
# the serve-loop seam
# --------------------------------------------------------------------------- #


def due_action(
    schedule: Schedule,
    state: ScheduleState,
    ts: float,
    tz=None,
    actual_on: bool | None = None,
) -> str | None:
    """``"on"`` / ``"off"`` when the loop must act now, else ``None``.

    This is the only decision the serve loop needs, and it is pure so the whole
    behaviour is testable without BLE:

      * window disabled, or paused by a manual override -> ``None``
      * the wanted state differs from what we last pushed -> flip (this is the
        boundary crossing, the first run after start, and a config change)
      * we already pushed it, but the charger reports the other state and the
        re-apply interval has passed -> push again (a hand flip, a lost write or
        a pulse left it disagreeing with the window)
      * otherwise -> ``None`` (idle; no GATT session, no log spam)
    """
    if not schedule.enabled:
        return None
    if schedule.is_overridden(ts):
        return None
    wants = schedule.wants_on(ts, tz)
    if state.applied is None or state.applied != wants:
        return "on" if wants else "off"
    if actual_on is None or actual_on == wants:
        return None
    if state.last_attempt and (ts - state.last_attempt) < REAPPLY_S:
        return None
    return "on" if wants else "off"


def allows_pulse(schedule: Schedule, ts: float, actual_on: bool | None = None, tz=None) -> bool:
    """May the yield watchdog pulse (off -> on) right now?

    The watchdog's rule is "never leave the charger off", and a pulse always
    ends with the charger ON — so it must not fight the window. Pulsing after
    the evening OFF would silently undo what the user asked for, and pulsing
    during a manual OFF would undo a deliberate hand flip.

      * window disabled            -> allow (the watchdog has no opinion to fight)
      * overridden by a hand flip  -> allow only while the charger is actually ON
        (unknown counts as ON so an unresponsive read does not disable the watchdog)
      * otherwise                  -> allow only inside the window
    """
    if not schedule.enabled:
        return True
    if schedule.is_overridden(ts):
        return True if actual_on is None else bool(actual_on)
    return schedule.wants_on(ts, tz)


def pulse_blocked_reason(schedule: Schedule, ts: float, actual_on: bool | None = None, tz=None) -> str | None:
    """Why the watchdog is standing down, for the page's "will not pulse" line."""
    if allows_pulse(schedule, ts, actual_on, tz):
        return None
    nxt = schedule.next_change_ts(ts, tz)
    if schedule.is_overridden(ts):
        return "manual ON/OFF has the charger off until %s" % human_time(format_minutes(local_minutes(nxt, tz)))
    return "automatic OFF until %s" % human_time(format_minutes(local_minutes(nxt, tz)))


def note_attempt(state: ScheduleState, action: str, ok: bool, message: str, ts: float) -> None:
    """Record the outcome of a push so [due_action] can pace re-applies."""
    state.last_attempt = ts
    state.last_ok = ok
    state.last_message = message
    state.last_action = action
    state.last_action_at = ts
    if ok:
        state.applied = action == "on"


def status_payload(schedule: Schedule, state: ScheduleState, ts: float, tz=None) -> dict:
    """The ``schedule`` object served on ``GET /charger/status``."""
    nxt = schedule.next_change_ts(ts, tz)
    override = schedule.override_until if schedule.is_overridden(ts) else 0
    return {
        "enabled": bool(schedule.enabled),
        "onTime": schedule.on_time,
        "offTime": schedule.off_time,
        "onTimeText": human_time(schedule.on_time),
        "offTimeText": human_time(schedule.off_time),
        "wantsOn": bool(schedule.wants_on(ts, tz)),
        "nextTransition": format_minutes(local_minutes(nxt, tz)),
        "nextTransitionTs": int(nxt),
        "nextTransitionInS": int(max(0, nxt - ts)),
        "nextTransitionText": "%s at %s"
        % (
            "ON" if not schedule.wants_on(ts, tz) else "OFF",
            human_time(format_minutes(local_minutes(nxt, tz))),
        ),
        "overrideUntil": int(override),
        "overridden": bool(schedule.is_overridden(ts)),
        "summary": schedule.describe(ts, tz),
        "zone": local_zone_name(ts, tz),
        "source": schedule.source,
        "lastAction": state.last_action or None,
        "lastActionAt": int(state.last_action_at) if state.last_action_at else None,
        "lastOk": bool(state.last_ok),
        "lastMessage": state.last_message or None,
        "appliedOn": state.applied,
    }


#: Accepted spellings of the two edges, so a caller from the Android half of this
#: repo (``enable``/``disable``) and the Linux page (``on``/``off``) both work.
_ON_KEYS = ("on", "enable", "enableTime", "onTime")
_OFF_KEYS = ("off", "disable", "disableTime", "offTime")


def parse_request(body: object, current: Schedule) -> tuple[Schedule | None, str | None]:
    """Parse a ``POST /charger/schedule`` body -> ``(schedule, None)`` or ``(None, error)``.

    ``{"enabled":true,"on":"06:45","off":"17:30"}``. An omitted edge keeps the
    configured one, so the page can save just the toggle. Saving also clears any
    manual override: setting a window means "run this window", which is what the
    Android half does too.
    """
    if not isinstance(body, dict):
        return None, 'body must be JSON: {"enabled":true,"on":"HH:MM","off":"HH:MM"}'

    enabled = None
    if "enabled" in body and body["enabled"] is not None:
        enabled = coerce_flag(body["enabled"])
        if enabled is None:
            return None, "enabled must be true or false"

    def edge(keys: tuple[str, ...], label: str, example: str, fallback: str) -> tuple[str, str | None]:
        for key in keys:
            if key not in body or body[key] is None:
                continue
            raw = str(body[key]).strip()
            if not raw:
                continue
            if parse_minutes(raw) is None:
                return fallback, "%s must look like %s (HH:MM, 24-hour)" % (label, example)
            return format_minutes(parse_minutes(raw) or 0), None
        return fallback, None

    on_time, err = edge(_ON_KEYS, "on", DEFAULT_ON, current.on_time)
    if err:
        return None, err
    off_time, err = edge(_OFF_KEYS, "off", DEFAULT_OFF, current.off_time)
    if err:
        return None, err

    return replace(current, enabled=current.enabled if enabled is None else bool(enabled), on_time=on_time, off_time=off_time, override_until=0.0, source="http"), None
