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
    PvSchedule,
    ScheduleController,
    coerce_enabled,
    format_minutes,
    is_in_window,
    next_transition,
    next_transition_at,
    normalize_config,
    normalize_pv_config,
    parse_hhmm,
    pv_sleep_due,
    pv_wake_ready,
    validated_config,
    validated_pv_config,
)

UTC = dt.timezone.utc


class FakeClock:
    def __init__(self, start: float = 0.0):
        self.now = start

    def __call__(self) -> float:
        return self.now


class FakeBle:
    """Records BLE traffic; ``mode`` is the device's current bool state."""

    def __init__(
        self,
        mode: bool | None = None,
        read_ok: bool = True,
        apply_ok: bool = True,
        apply_echo: bool = True,
    ):
        self.mode = mode
        self.read_ok = read_ok
        self.apply_ok = apply_ok
        self.apply_echo = apply_echo
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
        if not self.apply_echo:
            return ModeResult(True, None, "wrote mode; no GATT echo — treat write as accepted")
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
        with self.assertRaises(ValueError):
            validated_config(True, "07:00", "18:00", {"wakeAfter": "nope"})
        entry = validated_config("false", "6:30", "18:00")
        self.assertFalse(entry["enabled"])
        self.assertEqual("06:30", entry["enable_time"])
        self.assertEqual("18:00", entry["disable_time"])
        self.assertEqual(normalize_pv_config(None), entry["pv"])

    def test_validated_pv_config_roundtrip(self):
        pv = validated_pv_config(
            {
                "enabled": "false",
                "wakeAfter": "05:15",
                "wakePanelV": 75,
                "sleepAfter": "16:45",
                "sleepWatts": 35,
            }
        )
        self.assertFalse(pv["enabled"])
        self.assertEqual("05:15", pv["wake_after"])
        self.assertEqual(75.0, pv["wake_panel_v"])
        self.assertEqual("16:45", pv["sleep_after"])
        self.assertEqual(35.0, pv["sleep_watts"])

    def test_normalize_config_falls_back_to_defaults(self):
        defaults = normalize_config({"enabled": "nonsense", "enable_time": "nope"})
        self.assertTrue(defaults["enabled"])
        self.assertEqual("07:00", defaults["enable_time"])
        self.assertEqual("18:00", defaults["disable_time"])
        self.assertEqual(normalize_pv_config(None), defaults["pv"])
        configured = normalize_config(
            {
                "enabled": False,
                "enableTime": "05:15",
                "disableTime": "21:45",
                "pv": {"wakeAfter": "05:30", "wakePanelV": 70, "sleepAfter": "16:00", "sleepWatts": 30},
            }
        )
        self.assertFalse(configured["enabled"])
        self.assertEqual("05:15", configured["enable_time"])
        self.assertEqual("21:45", configured["disable_time"])
        self.assertEqual("05:30", configured["pv"]["wake_after"])
        self.assertEqual(70.0, configured["pv"]["wake_panel_v"])


class ConfigPersistenceTest(unittest.TestCase):
    def test_save_load_roundtrip_preserves_other_keys_and_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "devices.json"
            path.write_text(json.dumps({"mac": "AA:BB", "keys": {"AA:BB": "00"}}))
            saved = save_schedule(
                True,
                "6:30",
                "18:00",
                pv={
                    "enabled": True,
                    "wakeAfter": "05:15",
                    "wakePanelV": 70,
                    "sleepAfter": "16:30",
                    "sleepWatts": 35,
                },
                path=path,
            )
            self.assertEqual("06:30", saved["enable_time"])
            self.assertEqual("05:15", saved["pv"]["wake_after"])
            data = json.loads(path.read_text())
            self.assertEqual("AA:BB", data["mac"])
            self.assertEqual("00", data["keys"]["AA:BB"])
            loaded = load_schedule(path)
            self.assertEqual("18:00", loaded["disable_time"])
            self.assertEqual("16:30", loaded["pv"]["sleep_after"])
            self.assertEqual(35.0, loaded["pv"]["sleep_watts"])
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

    async def test_read_failure_blind_applies(self):
        # This SmartSolar often does not echo the mode register; the window
        # must still be enforced with an idempotent write.
        ble = FakeBle(mode=None, read_ok=False)
        ctl, _ = make_controller(ble)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([False], ble.applies)  # 03:00 wants OFF
        self.assertIs(False, ctl.last_mode)
        self.assertIsNone(ctl.last_error)

    async def test_blind_apply_without_gatt_echo_is_success(self):
        ble = FakeBle(mode=None, read_ok=False, apply_echo=False)
        ctl, _ = make_controller(ble)
        await ctl.tick()
        self.assertEqual([False], ble.applies)
        self.assertIs(False, ctl.last_mode)  # trust the accepted write
        self.assertIsNone(ctl.last_error)

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

    async def test_blind_apply_failure_backs_off_then_retries(self):
        ble = FakeBle(mode=None, read_ok=False, apply_ok=False)
        clock = FakeClock(utc_ts(8))  # window wants ON
        ctl, _ = make_controller(ble, clock, retry_s=120)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([True], ble.applies)  # read failed -> blind apply attempt
        self.assertIsNotNone(ctl.last_error)
        clock.now += 60
        await ctl.tick()
        self.assertEqual(1, ble.reads)  # still backing off
        self.assertEqual([True], ble.applies)
        ble.read_ok = True
        ble.apply_ok = True
        ble.mode = False  # device is back, charger still off
        clock.now += 61
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([True, True], ble.applies)
        self.assertIsNone(ctl.last_error)

    async def test_boundary_resets_backoff_after_failure(self):
        ble = FakeBle(mode=None, read_ok=False, apply_ok=False)
        clock = FakeClock(utc_ts(3))
        ctl, _ = make_controller(ble, clock)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([False], ble.applies)  # blind OFF attempt fails too
        self.assertIsNotNone(ctl.last_error)
        clock.now += 60
        await ctl.tick()
        self.assertEqual(1, ble.reads)  # backing off
        ble.read_ok = True
        ble.apply_ok = True
        ble.mode = False  # charger is off at 7am; window wants ON
        clock.now = utc_ts(7, 0)  # boundary resets backoff -> immediate retry
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([False, True], ble.applies)
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


class PvGateTest(unittest.TestCase):
    def test_helpers(self):
        pv = PvSchedule(
            wake_after=5 * 60,
            wake_panel_v=60.0,
            sleep_after=17 * 60,
            sleep_watts=40.0,
        )
        self.assertTrue(pv_wake_ready(5 * 60, 60.0, pv))
        self.assertFalse(pv_wake_ready(5 * 60 - 1, 100.0, pv))
        self.assertFalse(pv_wake_ready(5 * 60, 59.9, pv))
        self.assertFalse(pv_wake_ready(5 * 60, None, pv))
        self.assertTrue(pv_sleep_due(17 * 60, 0.0, pv))
        self.assertFalse(pv_sleep_due(17 * 60 - 1, 0.0, pv))
        self.assertFalse(pv_sleep_due(17 * 60, 40.0, pv))
        self.assertFalse(pv_sleep_due(17 * 60, None, pv))

    def test_from_mapping_parses_and_defaults(self):
        pv = PvSchedule.from_mapping(
            {
                "enabled": "false",
                "wake_after": "06:30",
                "wakePanelV": 75,
                "sleepAfter": "16:45",
                "sleepWatts": 30,
            }
        )
        self.assertFalse(pv.enabled)
        self.assertEqual(6 * 60 + 30, pv.wake_after)
        self.assertEqual(75.0, pv.wake_panel_v)
        self.assertEqual(16 * 60 + 45, pv.sleep_after)
        self.assertEqual(30.0, pv.sleep_watts)
        defaults = PvSchedule.from_mapping({"wake_after": "nonsense"})
        self.assertEqual(5 * 60, defaults.wake_after)
        self.assertEqual(17 * 60, defaults.sleep_after)

    def test_bad_numbers_are_clamped(self):
        pv = normalize_pv_config({"wakePanelV": float("nan"), "sleepWatts": -10})
        self.assertEqual(60.0, pv["wake_panel_v"])  # NaN -> default
        self.assertEqual(0.0, pv["sleep_watts"])  # negative -> 0


class PvControllerTest(unittest.IsolatedAsyncioTestCase):
    def _ctl(self, ble, clock, *, panel=80.0, watts=300.0, pv=None, **kwargs):
        holder = {"panel": panel, "watts": watts}
        ctl = ScheduleController(
            {"enabled": True, "enable_time": "07:00", "disable_time": "18:00"},
            ble.read,
            ble.apply,
            tz=UTC,
            now_fn=clock,
            pv=pv
            or PvSchedule(
                wake_after=5 * 60,
                wake_panel_v=60.0,
                sleep_after=17 * 60,
                sleep_watts=40.0,
            ),
            watts=lambda: holder["watts"],
            panel_v=lambda: holder["panel"],
            **kwargs,
        )
        return ctl, holder

    async def test_wakes_before_base_window_on_panel_voltage(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(5, 30))
        ctl, _ = self._ctl(ble, clock, panel=70.0)
        await ctl.tick()
        self.assertEqual([True], ble.applies)
        self.assertTrue(ctl.snapshot()["inWindow"])
        self.assertTrue(ctl.snapshot()["pv"]["morningStarted"])

    async def test_does_not_wake_before_0500_or_below_threshold(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(4, 30))
        ctl, _ = self._ctl(ble, clock, panel=90.0)
        await ctl.tick()
        self.assertEqual([], ble.applies)
        self.assertFalse(ctl.snapshot()["inWindow"])
        clock.now = utc_ts(5, 30)
        ctl2 = ScheduleController(
            {"enabled": True, "enable_time": "07:00", "disable_time": "18:00"},
            ble.read,
            ble.apply,
            tz=UTC,
            now_fn=clock,
            pv=PvSchedule(wake_panel_v=60.0),
            panel_v=lambda: 40.0,
            watts=lambda: 0.0,
        )
        await ctl2.tick()
        self.assertFalse(ctl2.snapshot()["inWindow"])

    async def test_sleeps_early_once_output_collapses(self):
        ble = FakeBle(mode=True)
        clock = FakeClock(utc_ts(17, 30))
        ctl, _ = self._ctl(ble, clock, panel=80.0, watts=10.0)
        await ctl.tick()
        self.assertEqual([False], ble.applies)
        self.assertFalse(ctl.snapshot()["inWindow"])  # base window still ON
        self.assertTrue(ctl.snapshot()["pv"]["eveningEnded"])
        self.assertFalse(ctl.snapshot()["pv"]["morningStarted"])

    async def test_keeps_base_window_while_still_generating(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(17, 30))
        ctl, _ = self._ctl(ble, clock, panel=80.0, watts=500.0)
        await ctl.tick()
        self.assertEqual([True], ble.applies)
        self.assertFalse(ctl.snapshot()["pv"]["eveningEnded"])
        self.assertFalse(ctl.snapshot()["pv"]["morningStarted"])

    async def test_missing_readings_never_latch(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(5, 30))
        ctl, holder = self._ctl(ble, clock, panel=None, watts=None)
        await ctl.tick()
        self.assertEqual([], ble.applies)
        self.assertFalse(ctl.snapshot()["pv"]["morningStarted"])
        clock.now = utc_ts(17, 30)
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # base ON, no sleep latch
        self.assertFalse(ctl.snapshot()["pv"]["eveningEnded"])

    async def test_latches_reset_next_local_day(self):
        ble = FakeBle(mode=True)
        clock = FakeClock(utc_ts(17, 30, day=24))
        ctl, holder = self._ctl(ble, clock, panel=80.0, watts=10.0)
        await ctl.tick()  # PV sleep
        self.assertFalse(ctl.snapshot()["inWindow"])
        clock.now = utc_ts(18, 30, day=24)
        await ctl.tick()  # base OFF; latch still holds
        self.assertEqual([False], ble.applies)
        holder["panel"] = 80.0
        clock.now = utc_ts(5, 30, day=25)
        await ctl.tick()  # new day, PV wake
        self.assertEqual([False, True], ble.applies)
        self.assertTrue(ctl.snapshot()["inWindow"])

    async def test_overnight_window_ignores_pv_gating(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(20))
        ctl = ScheduleController(
            {"enabled": True, "enable_time": "18:00", "disable_time": "07:00"},
            ble.read,
            ble.apply,
            tz=UTC,
            now_fn=clock,
            pv=PvSchedule(wake_panel_v=60.0, sleep_watts=40.0),
            panel_v=lambda: 200.0,
            watts=lambda: 0.0,
        )
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # overnight ON, not trimmed by PV

    async def test_disabled_pv_falls_back_to_base_window(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(5, 30))
        ctl, _ = self._ctl(ble, clock, panel=200.0, pv=PvSchedule(enabled=False))
        await ctl.tick()
        self.assertEqual([], ble.applies)
        self.assertFalse(ctl.snapshot()["inWindow"])

    async def test_config_update_applies_pv_rules(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(5, 30))
        ctl, _ = self._ctl(ble, clock, panel=90.0, pv=PvSchedule(enabled=False))
        await ctl.tick()
        self.assertEqual([], ble.applies)  # PV disabled -> base OFF at 05:30
        ctl.update_config(
            {
                "enabled": True,
                "enable_time": "07:00",
                "disable_time": "18:00",
                "pv": {
                    "enabled": True,
                    "wake_after": "05:00",
                    "wake_panel_v": 60.0,
                    "sleep_after": "17:00",
                    "sleep_watts": 40.0,
                },
            }
        )
        await ctl.tick()
        self.assertEqual([True], ble.applies)  # newly enabled PV rules wake it
        self.assertTrue(ctl.snapshot()["pv"]["morningStarted"])


if __name__ == "__main__":
    unittest.main()
