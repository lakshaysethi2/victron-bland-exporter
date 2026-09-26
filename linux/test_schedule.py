"""Sun-driven charger schedule: learned levels, config persistence, and the enforcer."""

from __future__ import annotations

import datetime as dt
import json
import stat
import tempfile
import unittest
from pathlib import Path

from mppt_ble.config import load_schedule, save_schedule
from mppt_ble.schedule import (
    DEFAULT_SLEEP_FRAC,
    DEFAULT_WAKE_FRAC,
    Learned,
    ModeResult,
    ScheduleController,
    coerce_enabled,
    normalize_config,
    sun_down,
    sun_up,
    validated_config,
)

UTC = dt.timezone.utc

# 231 V peak, 3 V night floor, 1400 W today -> wake at 3 + 0.5 * 228 = 117 V, sleep below 70 W.
LEARNED = {"pv_max": 231.0, "pv_night": 3.0, "watts_peak_today": 1400.0, "days": 5}
WAKE_V = 117.0
SLEEP_W = 70.0


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
    ble: FakeBle,
    clock: FakeClock | None = None,
    *,
    panel: float | None = 200.0,
    watts: float | None = 800.0,
    bus: float | None = 40.0,
    history: object = LEARNED,
    **kwargs,
):
    clock = clock or FakeClock(utc_ts(3))
    live = {"panel": panel, "watts": watts, "bus": bus}
    ctl = ScheduleController(
        {"enabled": True, "wake_frac": 0.5, "sleep_frac": 0.05},
        ble.read,
        ble.apply,
        tz=UTC,
        now_fn=clock,
        panel_v=lambda: live["panel"],
        watts=lambda: live["watts"],
        bus_v=lambda: live["bus"],
        history=(lambda: history) if history is not None else None,
        **kwargs,
    )
    return ctl, clock, live


class LearnedTest(unittest.TestCase):
    def test_wake_level_sits_between_night_floor_and_peak(self):
        learned = Learned.from_mapping(LEARNED)
        self.assertEqual(WAKE_V, learned.wake_v(0.5))
        self.assertEqual(3.0, learned.wake_v(0.0))
        self.assertEqual(231.0, learned.wake_v(1.0))
        self.assertEqual(5, learned.days)

    def test_sleep_level_is_a_share_of_todays_peak(self):
        learned = Learned.from_mapping(LEARNED)
        self.assertEqual(SLEEP_W, learned.sleep_w(0.05))

    def test_missing_or_degenerate_history_has_no_levels(self):
        for raw in (None, {}, {"pv_max": 200.0}, {"pv_max": 200.0, "pv_night": 200.0}):
            self.assertIsNone(Learned.from_mapping(raw).wake_v(0.5), raw)
        self.assertIsNone(Learned.from_mapping({}).sleep_w(0.05))
        self.assertIsNone(
            Learned.from_mapping({"watts_peak_today": 0.0}).sleep_w(0.05)
        )

    def test_from_mapping_ignores_junk(self):
        learned = Learned.from_mapping(
            {"pv_max": "231", "pv_night": None, "watts_peak_today": "nan", "days": "3"}
        )
        self.assertEqual(231.0, learned.pv_max)
        self.assertIsNone(learned.pv_night)
        self.assertIsNone(learned.watts_peak_today)
        self.assertEqual(3, learned.days)


class SunTest(unittest.TestCase):
    def test_learned_level_decides_both_ways(self):
        self.assertTrue(sun_up(200.0, 40.0, WAKE_V))
        self.assertFalse(sun_up(40.0, 40.0, WAKE_V))
        self.assertTrue(sun_down(40.0, 40.0, WAKE_V))
        self.assertFalse(sun_down(200.0, 40.0, WAKE_V))

    def test_no_panel_reading_is_never_evidence(self):
        self.assertFalse(sun_up(None, 40.0, WAKE_V))
        self.assertFalse(sun_down(None, 40.0, WAKE_V))

    def test_bootstrap_scales_off_the_bus(self):
        self.assertTrue(sun_up(90.0, 40.0, None))
        self.assertFalse(sun_up(70.0, 40.0, None))
        self.assertTrue(sun_down(30.0, 40.0, None))
        self.assertFalse(sun_down(90.0, 40.0, None))

    def test_without_learning_or_bus_nothing_latches(self):
        self.assertFalse(sun_up(200.0, None, None))
        self.assertFalse(sun_down(0.0, None, None))
        self.assertFalse(sun_up(200.0, 0.0, None))


class ConfigTest(unittest.TestCase):
    def test_normalize_defaults(self):
        self.assertEqual(
            {"enabled": True, "wake_frac": 0.5, "sleep_frac": 0.05},
            normalize_config({}),
        )
        self.assertEqual(DEFAULT_WAKE_FRAC, 0.5)
        self.assertEqual(DEFAULT_SLEEP_FRAC, 0.05)

    def test_normalize_accepts_camel_case_and_strings(self):
        self.assertEqual(
            {"enabled": False, "wake_frac": 0.7, "sleep_frac": 0.02},
            normalize_config({"enabled": "off", "wakeFrac": "0.7", "sleep_frac": 0.02}),
        )

    def test_normalize_ignores_out_of_range_instead_of_clamping(self):
        self.assertEqual(0.5, normalize_config({"wake_frac": 50})["wake_frac"])
        self.assertEqual(0.5, normalize_config({"wake_frac": -1})["wake_frac"])
        self.assertEqual(0.05, normalize_config({"sleep_frac": "abc"})["sleep_frac"])

    def test_coerce_enabled_accepts_strings(self):
        for value in ("off", "0", "false", "no", "disabled", "", None):
            self.assertFalse(coerce_enabled(value), value)
        for value in ("on", "1", "true", True):
            self.assertTrue(coerce_enabled(value), value)

    def test_validated_config_rejects_bad_fracs(self):
        self.assertEqual(0.5, validated_config(True)["wake_frac"])
        self.assertEqual(
            {"enabled": True, "wake_frac": 0.8, "sleep_frac": 0.1},
            validated_config("on", 0.8, 0.1),
        )
        for bad in ("abc", 5, -0.1, 1.01):
            with self.assertRaises(ValueError):
                validated_config(True, bad, 0.05)
            with self.assertRaises(ValueError):
                validated_config(True, 0.5, bad)


class ConfigPersistenceTest(unittest.TestCase):
    def test_save_load_roundtrip_preserves_other_keys_and_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "devices.json"
            path.write_text(json.dumps({"mac": "AA:BB", "keys": {"x": "y"}}))
            saved = save_schedule(True, 0.6, 0.03, path=path)
            self.assertEqual(0.6, saved["wake_frac"])
            stored = json.loads(path.read_text())
            self.assertEqual("AA:BB", stored["mac"])
            self.assertEqual({"enabled": True, "wake_frac": 0.6, "sleep_frac": 0.03}, stored["schedule"])
            self.assertEqual(saved, load_schedule(path))
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

    def test_missing_file_returns_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(
                {"enabled": True, "wake_frac": 0.5, "sleep_frac": 0.05},
                load_schedule(Path(tmp) / "nope.json"),
            )

    def test_invalid_save_is_rejected_without_touching_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "devices.json"
            save_schedule(True, 0.5, 0.05, path=path)
            before = path.read_text()
            with self.assertRaises(ValueError):
                save_schedule(True, 12, 0.05, path=path)
            self.assertEqual(before, path.read_text())


class ScheduleControllerTest(unittest.IsolatedAsyncioTestCase):
    async def test_sun_up_latches_on(self):
        ble = FakeBle(mode=False)  # 09:00, panel 200 V -> wants ON
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(9)))
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([True], ble.applies)
        self.assertTrue(ctl.snapshot()["sunriseLatched"])
        self.assertIsNone(ctl.last_error)

    async def test_no_sunrise_yet_is_off(self):
        ble = FakeBle(mode=True)  # 03:00, panel 4 V
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(3)), panel=4.0, watts=0.0)
        await ctl.tick()
        self.assertEqual([False], ble.applies)
        self.assertFalse(ctl.snapshot()["sunriseLatched"])

    async def test_full_battery_at_noon_does_not_latch_sunset(self):
        # 0 W with a live panel is a full battery, not a sunset.
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        self.assertEqual([], ble.applies)
        live["watts"] = 0.0
        clock.now = utc_ts(14)
        await ctl.tick()
        self.assertEqual([], ble.applies)
        self.assertFalse(ctl.snapshot()["sunsetLatched"])
        self.assertTrue(ctl.snapshot()["sunUp"])

    async def test_sunset_latches_off_after_a_wake(self):
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        live.update({"panel": 8.0, "watts": 5.0})
        clock.now = utc_ts(19)
        await ctl.tick()
        self.assertEqual([False], ble.applies)
        self.assertTrue(ctl.snapshot()["sunsetLatched"])

    async def test_low_output_with_a_live_panel_keeps_charging(self):
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        live["watts"] = 5.0  # below sleep_w, but the panel is bright
        await ctl.tick()
        self.assertEqual([], ble.applies)
        self.assertFalse(ctl.snapshot()["sunsetLatched"])

    async def test_restart_after_dark_latches_off(self):
        # No sunrise latch (fresh process at dusk) still stops the charger.
        ble = FakeBle(mode=True)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(21)), panel=6.0, watts=0.0)
        await ctl.tick()
        self.assertEqual([False], ble.applies)

    async def test_sun_coming_back_clears_the_sunset_latch(self):
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        live.update({"panel": 30.0, "watts": 10.0})
        clock.now = utc_ts(15)
        await ctl.tick()
        self.assertEqual([False], ble.applies)
        live.update({"panel": 210.0, "watts": 900.0})
        clock.now = utc_ts(16)
        await ctl.tick()
        self.assertEqual([False, True], ble.applies)
        self.assertFalse(ctl.snapshot()["sunsetLatched"])

    async def test_latches_reset_at_midnight(self):
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        self.assertTrue(ctl.snapshot()["sunriseLatched"])
        clock.now = utc_ts(0, 5, day=25)
        live.update({"panel": 3.0, "watts": 0.0})
        await ctl.tick()
        self.assertFalse(ctl.snapshot()["sunriseLatched"])  # a new day starts clean
        self.assertTrue(ctl.snapshot()["sunsetLatched"])
        self.assertEqual([False], ble.applies)

    async def test_bootstrap_without_history(self):
        ble = FakeBle(mode=False)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(9)), history=None)
        await ctl.tick()  # panel 200 V >= 2 x 40 V
        self.assertEqual([True], ble.applies)
        self.assertTrue(ctl.snapshot()["learned"]["bootstrap"])
        dark = FakeBle(mode=False)
        ctl2, _, _ = make_controller(dark, FakeClock(utc_ts(9)), panel=70.0, history=None)
        await ctl2.tick()  # 70 V is under 2 x bus: no wake, and no reason to write
        self.assertEqual([], dark.applies)
        self.assertFalse(ctl2.snapshot()["sunriseLatched"])
        self.assertFalse(ctl2.snapshot()["desiredOn"])

    async def test_missing_panel_reading_leaves_the_state_alone(self):
        ble = FakeBle(mode=True)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(9)), panel=None)
        await ctl.tick()
        self.assertEqual([], ble.applies)  # no opinion, so nothing is written
        self.assertIsNone(ctl.snapshot()["desiredOn"])
        ble2 = FakeBle(mode=False)
        ctl2, _, _ = make_controller(ble2, FakeClock(utc_ts(9)), panel=None)
        await ctl2.tick()
        self.assertEqual([], ble2.applies)
        self.assertEqual("no panel reading yet", ctl2.snapshot()["reason"])

    async def test_no_output_reported_counts_as_collapsed(self):
        # The unit sleeps at night and stops reporting output; a dark panel then means sunset.
        ble = FakeBle(mode=True)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(20)), panel=6.0, watts=None)
        await ctl.tick()
        self.assertEqual([False], ble.applies)

    async def test_disabled_schedule_never_touches_ble(self):
        ble = FakeBle(mode=True)
        ctl, _, _ = make_controller(ble)
        ctl.config = normalize_config({"enabled": False})
        await ctl.tick()
        self.assertEqual(0, ble.reads)
        self.assertEqual([], ble.applies)

    async def test_manual_override_parks_until_the_sun_changes(self):
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        ctl.note_manual(False)  # user turns it off by hand at noon
        ble.mode = False
        self.assertTrue(ctl.snapshot()["override"])
        clock.now = utc_ts(13)
        await ctl.tick()
        self.assertEqual([], ble.applies)  # the schedule does not fight the user
        live.update({"panel": 6.0, "watts": 0.0})
        clock.now = utc_ts(20)
        await ctl.tick()
        self.assertFalse(ctl.snapshot()["override"])
        self.assertEqual([], ble.applies)  # already off at sunset

    async def test_manual_on_at_night_holds_until_sunrise(self):
        ble = FakeBle(mode=True)
        ctl, clock, live = make_controller(ble, FakeClock(utc_ts(23)), panel=4.0, watts=0.0)
        await ctl.tick()
        self.assertEqual([False], ble.applies)
        ble.mode = True
        ctl.note_manual(True)
        self.assertEqual([False], ble.applies)
        clock.now = utc_ts(1, 0, day=25)
        await ctl.tick()
        self.assertEqual([False], ble.applies)  # sunrise has not come yet
        live.update({"panel": 200.0, "watts": 700.0})
        clock.now = utc_ts(8, 0, day=25)
        await ctl.tick()
        self.assertFalse(ctl.snapshot()["override"])

    async def test_read_failure_blind_applies(self):
        # This SmartSolar often does not echo the mode register; the decision
        # must still be enforced with an idempotent write.
        ble = FakeBle(mode=None, read_ok=False)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(3)), panel=4.0)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([False], ble.applies)
        self.assertIs(False, ctl.last_mode)
        self.assertIsNone(ctl.last_error)

    async def test_blind_apply_without_gatt_echo_is_success(self):
        ble = FakeBle(mode=None, read_ok=False, apply_echo=False)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(9)))
        await ctl.tick()
        self.assertEqual([True], ble.applies)
        self.assertIs(True, ctl.last_mode)  # trust the accepted write
        self.assertIsNone(ctl.last_error)

    async def test_config_update_clears_override_and_enforces(self):
        ble = FakeBle(mode=True)
        ctl, _, live = make_controller(ble, FakeClock(utc_ts(12)))
        await ctl.tick()
        ctl.note_manual(True)
        live.update({"panel": 4.0, "watts": 0.0})
        ctl.update_config({"enabled": True, "wake_frac": 0.9, "sleep_frac": 0.05})
        self.assertFalse(ctl.snapshot()["override"])
        await ctl.tick()
        self.assertEqual([False], ble.applies)

    async def test_blind_apply_failure_backs_off_then_retries(self):
        ble = FakeBle(mode=None, read_ok=False, apply_ok=False)
        clock = FakeClock(utc_ts(9))  # sun up -> wants ON
        ctl, _, _ = make_controller(ble, clock, retry_s=120)
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

    async def test_state_flip_resets_backoff_after_failure(self):
        ble = FakeBle(mode=None, read_ok=False, apply_ok=False)
        clock = FakeClock(utc_ts(3))
        ctl, _, live = make_controller(ble, clock, panel=4.0)
        await ctl.tick()
        self.assertEqual([False], ble.applies)  # blind OFF attempt fails too
        self.assertIsNotNone(ctl.last_error)
        clock.now += 60
        await ctl.tick()
        self.assertEqual(1, ble.reads)  # backing off
        ble.read_ok = True
        ble.apply_ok = True
        ble.mode = False  # charger is off; the sun comes up
        live.update({"panel": 220.0, "watts": 900.0})
        clock.now = utc_ts(7, 0)
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([False, True], ble.applies)
        self.assertIsNone(ctl.last_error)

    async def test_periodic_verify_catches_external_change(self):
        ble = FakeBle(mode=True)
        clock = FakeClock(utc_ts(12))
        ctl, _, _ = make_controller(ble, clock, verify_s=600)
        await ctl.tick()
        self.assertEqual(1, ble.reads)
        self.assertEqual([], ble.applies)
        ble.mode = False  # something else turned the charger off
        clock.now += 601
        await ctl.tick()
        self.assertEqual(2, ble.reads)
        self.assertEqual([True], ble.applies)

    async def test_failed_verify_reasserts_decision(self):
        # 27 Sep: the ON write was accepted without an echo, the unit stayed off, and every verify
        # failed - a failed verify must not leave the decision unenforced for the rest of the day.
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(9))
        ctl, _, _ = make_controller(ble, clock, verify_s=600)
        await ctl.tick()
        self.assertEqual([True], ble.applies)
        ble.read_ok = False  # mode register stops answering
        ble.apply_echo = False
        clock.now += 601
        await ctl.tick()
        self.assertEqual([True, True], ble.applies)  # re-asserted instead of trusting the write
        self.assertEqual(clock.now, ctl.last_applied_at)

    async def test_snapshot_reports_learned_levels_and_reason(self):
        ble = FakeBle(mode=False)
        ctl, _, _ = make_controller(ble, FakeClock(utc_ts(10, 30)))
        snap = ctl.snapshot()
        self.assertTrue(snap["enabled"])
        self.assertEqual(0.5, snap["wakeFrac"])
        self.assertEqual(0.05, snap["sleepFrac"])
        self.assertTrue(snap["desiredOn"])
        self.assertEqual("10:30", snap["serverTime"])
        self.assertEqual(231.0, snap["learned"]["pvMax"])
        self.assertEqual(WAKE_V, snap["learned"]["wakeV"])
        self.assertEqual(SLEEP_W, snap["learned"]["sleepW"])
        self.assertFalse(snap["learned"]["bootstrap"])
        self.assertFalse(snap["override"])
        self.assertIn("sun up", snap["reason"])

    async def test_learned_levels_refresh_from_history(self):
        ble = FakeBle(mode=False)
        clock = FakeClock(utc_ts(10))
        live_history = {"pv_max": 231.0, "pv_night": 3.0, "watts_peak_today": 1400.0}
        ctl, _, _ = make_controller(ble, clock, history=None)
        ctl._history = lambda: dict(live_history)
        snap = ctl.snapshot()
        self.assertEqual(WAKE_V, snap["learned"]["wakeV"])
        live_history["pv_max"] = 400.0
        clock.now += 61
        self.assertEqual(3.0 + 0.5 * 397.0, ctl.snapshot()["learned"]["wakeV"])

    async def test_history_failure_keeps_the_last_levels(self):
        ble = FakeBle(mode=False)
        calls = {"n": 0}

        def boom():
            calls["n"] += 1
            raise RuntimeError("db gone")

        ctl, clock, _ = make_controller(ble, FakeClock(utc_ts(10)), history=None)
        ctl._history = boom
        first = ctl.snapshot()
        self.assertIsNone(first["learned"]["wakeV"])
        self.assertEqual(1, calls["n"])
        clock.now += 61
        ctl.snapshot()
        self.assertEqual(2, calls["n"])


if __name__ == "__main__":
    unittest.main()
