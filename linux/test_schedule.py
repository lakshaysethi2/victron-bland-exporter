"""Daily charger schedule: window math, config persistence, and the enforcer."""

from __future__ import annotations

import datetime as dt
import json
import stat
import tempfile
import unittest
from pathlib import Path

from mppt_ble.config import load_schedule, save_schedule
from mppt_ble.schedule import (
    DEFAULT_DISABLE,
    DEFAULT_ENABLE,
    ModeResult,
    ScheduleController,
    coerce_enabled,
    format_minutes,
    is_in_window,
    next_transition,
    next_transition_at,
    normalize_config,
    parse_hhmm,
    validated_config,
)

UTC = dt.timezone.utc


class FakeClock:
    def __init__(self, start: float = 0.0):
        self.now = start

    def __call__(self) -> float:
        return self.now


class FakeBle:
    """Records BLE traffic; ``mode`` is the device's current bool state."""

    def __init__(self, mode: bool | None = None, read_ok: bool = True, apply_ok: bool = True):
        self.mode = mode
        self.read_ok = read_ok
        self.apply_ok = apply_ok
        self.reads = 0
        self.applies: list[bool] = []

    async def read(self) -> ModeResult:
        self.reads += 1
        if not self.read_ok:
            return ModeResult(False, None, "no advertisement from AA:BB")
        return ModeResult(True, self.mode, "ok")

    async def apply(self, on: bool) -> ModeResult:
        self.applies.append(on)
        if not self.apply_ok:
            return ModeResult(False, None, "write failed")
        self.mode = on
        return ModeResult(True, on, "ok")


def utc_ts(hour: int, minute: int = 0, day: int = 24) -> float:
    return dt.datetime(2026, 9, day, hour, minute, tzinfo=UTC).timestamp()


def make_controller(
    ble: FakeBle, clock: FakeClock | None = None, **kwargs
) -> tuple[ScheduleController, FakeClock]:
    clock = clock or FakeClock(utc_ts(3))
    ctl = ScheduleController(
        {"enabled": True, "enable_time": "07:00", "disable_time": "18:00"},
        ble.read,
        ble.apply,
        tz=UTC,
        now_fn=clock,
        **kwargs,
    )
    return ctl, clock


class WindowMathTest(unittest.TestCase):
    def test_defaults_are_07_00_and_18_00(self):
        self.assertEqual(7 * 60, parse_hhmm(DEFAULT_ENABLE))
        self.assertEqual(18 * 60, parse_hhmm(DEFAULT_DISABLE))

    def test_parses_valid_times(self):
        self.assertEqual(0, parse_hhmm("00:00"))
        self.assertEqual(7 * 60 + 5, parse_hhmm("7:05"))
        self.assertEqual(23 * 60 + 59, parse_hhmm("23:59"))

    def test_rejects_malformed_times(self):
        self.assertIsNone(parse_hhmm("25:00"))
        self.assertIsNone(parse_hhmm("08:60"))
        self.assertIsNone(parse_hhmm("830"))
        self.assertIsNone(parse_hhmm(""))
        self.assertIsNone(parse_hhmm(None))

    def test_format_wraps_and_zero_pads(self):
        self.assertEqual("07:00", format_minutes(7 * 60))
        self.assertEqual("00:00", format_minutes(0))
        self.assertEqual("00:00", format_minutes(1440))
        self.assertEqual("23:59", format_minutes(-1))

    def test_daytime_window(self):
        self.assertTrue(is_in_window(7 * 60, 7 * 60, 18 * 60))  # enable inclusive
        self.assertTrue(is_in_window(17 * 60 + 59, 7 * 60, 18 * 60))
        self.assertFalse(is_in_window(18 * 60, 7 * 60, 18 * 60))  # disable exclusive
        self.assertFalse(is_in_window(6 * 60 + 59, 7 * 60, 18 * 60))
        self.assertFalse(is_in_window(0, 7 * 60, 18 * 60))

    def test_overnight_window(self):
        self.assertTrue(is_in_window(20 * 60, 18 * 60, 7 * 60))
        self.assertTrue(is_in_window(3 * 60, 18 * 60, 7 * 60))
        self.assertFalse(is_in_window(12 * 60, 18 * 60, 7 * 60))
        self.assertFalse(is_in_window(7 * 60, 18 * 60, 7 * 60))

    def test_equal_times_means_always_on(self):
        self.assertTrue(is_in_window(12 * 60, 8 * 60, 8 * 60))
        self.assertTrue(is_in_window(0, 8 * 60, 8 * 60))
        self.assertIsNone(next_transition(12 * 60, 8 * 60, 8 * 60))

    def test_next_transition_is_nearest_future_boundary(self):
        self.assertEqual(18 * 60, next_transition(10 * 60, 7 * 60, 18 * 60))
        self.assertEqual(7 * 60, next_transition(19 * 60, 7 * 60, 18 * 60))
        self.assertEqual(18 * 60, next_transition(7 * 60, 7 * 60, 18 * 60))
        self.assertEqual(7 * 60, next_transition(20 * 60, 18 * 60, 7 * 60))
        self.assertEqual(7 * 60, next_transition(6 * 60, 18 * 60, 7 * 60))

    def test_next_transition_at_wraps_to_tomorrow(self):
        now = dt.datetime(2026, 9, 24, 19, 0, tzinfo=UTC)
        nxt = next_transition_at(now, 7 * 60, 18 * 60)
        self.assertEqual(dt.datetime(2026, 9, 25, 7, 0, tzinfo=UTC), nxt)

    def test_coerce_enabled_accepts_strings(self):
        self.assertTrue(coerce_enabled(True))
        self.assertTrue(coerce_enabled(1))
        self.assertTrue(coerce_enabled("true"))
        self.assertFalse(coerce_enabled(False))
        self.assertFalse(coerce_enabled(0))
        self.assertFalse(coerce_enabled("off"))

    def test_validated_config_rejects_bad_input(self):
        with self.assertRaises(ValueError):
            validated_config(True, "25:00", "18:00")
        with self.assertRaises(ValueError):
            validated_config(True, "07:00", "07:61")
        entry = validated_config("false", "6:30", "18:00")
        self.assertEqual(
            {"enabled": False, "enable_time": "06:30", "disable_time": "18:00"}, entry
        )

    def test_normalize_config_falls_back_to_defaults(self):
        self.assertEqual(
            {"enabled": True, "enable_time": "07:00", "disable_time": "18:00"},
            normalize_config({"enabled": "nonsense", "enable_time": "nope"}),
        )
        self.assertEqual(
            {"enabled": False, "enable_time": "05:15", "disable_time": "21:45"},
            normalize_config(
                {"enabled": False, "enableTime": "05:15", "disableTime": "21:45"}
            ),
        )


class ConfigPersistenceTest(unittest.TestCase):
    def test_save_load_roundtrip_preserves_other_keys_and_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "devices.json"
            path.write_text(json.dumps({"mac": "AA:BB", "keys": {"AA:BB": "00"}}))
            saved = save_schedule(True, "6:30", "18:00", path=path)
            self.assertEqual("06:30", saved["enable_time"])
            data = json.loads(path.read_text())
            self.assertEqual("AA:BB", data["mac"])
            self.assertEqual("00", data["keys"]["AA:BB"])
            self.assertEqual("18:00", load_schedule(path)["disable_time"])
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

    def test_missing_file_returns_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "nope.json"
            self.assertEqual(normalize_config(None), load_schedule(path))

    def test_invalid_save_is_rejected_without_touching_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "devices.json"
            path.write_text(json.dumps({"mac": "AA:BB"}))
            with self.assertRaises(ValueError):
                save_schedule(True, "25:00", "18:00", path=path)
            self.assertEqual({"mac": "AA:BB"}, json.loads(path.read_text()))


class ScheduleControllerTest(unittest.IsolatedAsyncioTestCase):
    async def test_unknown_mode_is_read_then_applied(self):
        ble = FakeBle(mode=True)  # 03:00 -> window wants OFF
        ctl, _ = make_controller(ble)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([False], ble.applies)
        self.assertIs(False, ctl.last_mode)
        self.assertEqual(False, ctl.last_applied_on)
        self.assertIsNone(ctl.last_error)

    async def test_matching_mode_is_not_rewritten(self):
        ble = FakeBle(mode=False)
        ctl, _ = make_controller(ble)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([], ble.applies)

    async def test_boundary_flip_applies_once(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(6, 59))
        ctl, _ = make_controller(ble, clock)
        await ctl.tick()
        self.assertEqual([], ble.applies)
        clock.now = utc_ts(7, 0)
        await ctl.tick()
        self.assertEqual([True], ble.applies)
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # no repeat inside the window
        clock.now = utc_ts(18, 0)
        await ctl.tick()
        self.assertEqual([True, False], ble.applies)

    async def test_overnight_window(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(20))
        ctl = ScheduleController(
            {"enabled": True, "enable_time": "18:00", "disable_time": "07:00"},
            ble.read,
            ble.apply,
            tz=UTC,
            now_fn=clock,
        )
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # evening: ON
        clock.now = utc_ts(6)
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # still inside overnight window
        clock.now = utc_ts(7)
        await ctl.tick()
        self.assertEqual([True, False], ble.applies)

    async def test_equal_times_always_on(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(3))
        ctl = ScheduleController(
            {"enabled": True, "enable_time": "08:00", "disable_time": "08:00"},
            ble.read,
            ble.apply,
            tz=UTC,
            now_fn=clock,
        )
        await ctl.tick()
        self.assertEqual([True], ble.applies)

    async def test_disabled_schedule_never_touches_ble(self):
        ble = FakeBle(mode=True)
        ctl, _ = make_controller(ble)
        ctl.config = normalize_config({"enabled": False})
        await ctl.tick()
        self.assertEqual(0, ble.reads)
        self.assertEqual([], ble.applies)

    async def test_manual_override_pauses_until_boundary(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(12))
        ctl, _ = make_controller(ble, clock)
        await ctl.tick()
        self.assertEqual([True], ble.applies)
        ctl.note_manual(False)  # user turned it off at noon
        ble.mode = False
        self.assertIsNotNone(ctl.snapshot()["overrideUntil"])
        clock.now = utc_ts(13)
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # window does not fight the user
        clock.now = utc_ts(18)
        await ctl.tick()
        self.assertIsNone(ctl.snapshot()["overrideUntil"])
        self.assertEqual([True], ble.applies)  # already OFF at the boundary

    async def test_config_update_clears_override_and_enforces(self):
        ble = FakeBle(mode=True)
        clock = FakeClock(utc_ts(12))
        ctl, _ = make_controller(ble, clock)
        await ctl.tick()
        ctl.note_manual(True)
        ctl.update_config(
            {"enabled": True, "enable_time": "18:00", "disable_time": "07:00"}
        )
        await ctl.tick()
        self.assertEqual(0.0, ctl.override_until)
        self.assertEqual([False], ble.applies)  # noon is outside the new window

    async def test_failure_backs_off_then_retries(self):
        ble = FakeBle(mode=True, read_ok=False)
        clock = FakeClock(utc_ts(3))
        ctl, _ = make_controller(ble, clock, retry_s=120)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertIsNotNone(ctl.last_error)
        clock.now += 60
        await ctl.tick()
        self.assertEqual(1, ble.reads)  # still backing off
        ble.read_ok = True
        clock.now += 61
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([False], ble.applies)
        self.assertIsNone(ctl.last_error)

    async def test_boundary_resets_backoff_after_failure(self):
        ble = FakeBle(mode=None, read_ok=False)
        clock = FakeClock(utc_ts(3))
        ctl, _ = make_controller(ble, clock)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertIsNotNone(ctl.last_error)
        clock.now += 60
        await ctl.tick()
        self.assertEqual(1, ble.reads)  # backing off
        ble.read_ok = True
        ble.mode = False  # charger is off at 7am; window wants ON
        clock.now = utc_ts(7, 0)  # boundary resets backoff -> immediate retry
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([True], ble.applies)
        self.assertIsNone(ctl.last_error)

    async def test_periodic_verify_catches_external_change(self):
        ble = FakeBle(mode=True)
        clock = FakeClock(utc_ts(12))
        ctl, _ = make_controller(ble, clock, verify_s=600)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([], ble.applies)
        ble.mode = False  # something else turned the charger off
        clock.now += 601
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([True], ble.applies)

    async def test_snapshot_reports_window_and_next_flip(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(10, 30))
        ctl, _ = make_controller(ble, clock)
        snap = ctl.snapshot()
        self.assertTrue(snap["enabled"])
        self.assertTrue(snap["inWindow"])
        self.assertEqual("07:00", snap["enableTime"])
        self.assertEqual("18:00", snap["disableTime"])
        self.assertEqual("18:00", snap["nextTransitionTime"])
        self.assertFalse(snap["nextTransitionOn"])
        self.assertEqual("10:30", snap["serverTime"])
        self.assertIsNone(snap["overrideUntil"])

    async def test_snapshot_next_flip_is_on_outside_window(self):
        ble = FakeBle(mode=True)
        clock = FakeClock(utc_ts(20))
        ctl, _ = make_controller(ble, clock)
        snap = ctl.snapshot()
        self.assertFalse(snap["inWindow"])
        self.assertEqual("07:00", snap["nextTransitionTime"])
        self.assertTrue(snap["nextTransitionOn"])


if __name__ == "__main__":
    unittest.main()
