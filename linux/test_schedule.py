"""Unit tests for the automatic daily ON/OFF window (issue #67).

Pure logic only: no BLE, no aiohttp, no host state. Timezone-sensitive cases
inject a fixed offset so the suite is deterministic on any machine.
"""

from __future__ import annotations

import json
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from mppt_ble import schedule as S

# A fixed +12:00 zone: no tzdata dependency, no DST surprises in the assertions.
TZ = timezone(timedelta(hours=12))


def ts_at(text: str, tz=TZ) -> float:
    """'2026-09-13 06:44' in [tz] -> epoch seconds."""
    return datetime.strptime(text, "%Y-%m-%d %H:%M").replace(tzinfo=tz).timestamp()


class TimeParsingTest(unittest.TestCase):
    def test_parses_zero_padded_and_single_digit_hours(self):
        self.assertEqual(405, S.parse_minutes("06:45"))
        self.assertEqual(405, S.parse_minutes("6:45"))
        self.assertEqual(1050, S.parse_minutes("17:30"))
        self.assertEqual(0, S.parse_minutes("00:00"))
        self.assertEqual(1439, S.parse_minutes("23:59"))

    def test_tolerates_surrounding_space(self):
        self.assertEqual(405, S.parse_minutes("  06:45 "))

    def test_rejects_malformed_times(self):
        for bad in ["", "24:00", "06:60", "6", "0645", "aa:bb", "06:45:00", "-6:45", None, 405]:
            self.assertIsNone(S.parse_minutes(bad), bad)
            self.assertFalse(S.is_valid_time(bad), bad)

    def test_formats_minutes_back_to_hhmm(self):
        self.assertEqual("06:45", S.format_minutes(405))
        self.assertEqual("00:00", S.format_minutes(0))
        self.assertEqual("23:59", S.format_minutes(1439))
        self.assertEqual("06:45", S.format_minutes(405 + 1440))  # wraps

    def test_defaults_stay_in_sync_with_their_minute_constants(self):
        self.assertEqual(S.parse_minutes(S.DEFAULT_ON), S.DEFAULT_ON_MINUTES)
        self.assertEqual(S.parse_minutes(S.DEFAULT_OFF), S.DEFAULT_OFF_MINUTES)

    def test_parses_a_window_string(self):
        self.assertEqual(("06:45", "17:30"), S.parse_window("06:45-17:30"))
        self.assertEqual(("06:45", "17:30"), S.parse_window(" 6:45 - 17:30 "))
        self.assertIsNone(S.parse_window("06:45"))
        self.assertIsNone(S.parse_window("25:00-17:30"))
        self.assertIsNone(S.parse_window(None))

    def test_human_time_is_twelve_hour_with_suffix(self):
        self.assertEqual("6:45 AM", S.human_time("06:45"))
        self.assertEqual("5:30 PM", S.human_time("17:30"))
        self.assertEqual("12:00 PM", S.human_time("12:00"))
        self.assertEqual("12:05 AM", S.human_time("00:05"))
        self.assertEqual("11:59 PM", S.human_time("23:59"))

    def test_countdown_is_coarse_and_never_negative(self):
        self.assertEqual("in under a minute", S.countdown(30))
        self.assertEqual("in under a minute", S.countdown(-900))
        self.assertEqual("in 5m", S.countdown(300))
        self.assertEqual("in 2h", S.countdown(7200))
        self.assertEqual("in 11h 32m", S.countdown(11 * 3600 + 32 * 60))


class WindowSemanticsTest(unittest.TestCase):
    """Issue #67's example: ON 06:45, OFF 17:30."""

    def test_daytime_window_is_on_between_the_edges(self):
        on, off = 405, 1050
        self.assertFalse(S.in_window(S.parse_minutes("06:44"), on, off))
        self.assertTrue(S.in_window(S.parse_minutes("06:45"), on, off))  # inclusive start
        self.assertTrue(S.in_window(S.parse_minutes("12:00"), on, off))
        self.assertFalse(S.in_window(S.parse_minutes("17:30"), on, off))  # exclusive end
        self.assertFalse(S.in_window(S.parse_minutes("23:00"), on, off))
        self.assertFalse(S.in_window(S.parse_minutes("00:00"), on, off))

    def test_overnight_window_wraps_midnight(self):
        on, off = S.parse_minutes("17:30"), S.parse_minutes("06:45")
        self.assertTrue(S.in_window(S.parse_minutes("17:30"), on, off))
        self.assertTrue(S.in_window(S.parse_minutes("23:59"), on, off))
        self.assertTrue(S.in_window(S.parse_minutes("00:00"), on, off))
        self.assertTrue(S.in_window(S.parse_minutes("06:44"), on, off))
        self.assertFalse(S.in_window(S.parse_minutes("06:45"), on, off))
        self.assertFalse(S.in_window(S.parse_minutes("12:00"), on, off))

    def test_equal_edges_are_a_24h_window_not_a_lockout(self):
        self.assertTrue(S.in_window(S.parse_minutes("03:00"), 405, 405))
        self.assertTrue(S.in_window(S.parse_minutes("17:31"), 405, 405))

    def test_next_transition_picks_the_nearest_future_edge(self):
        on, off = 405, 1050
        self.assertEqual(405, S.next_transition(S.parse_minutes("03:00"), on, off))
        self.assertEqual(1050, S.next_transition(S.parse_minutes("06:45"), on, off))
        self.assertEqual(1050, S.next_transition(S.parse_minutes("12:00"), on, off))
        # both edges passed today -> tomorrow's first edge
        self.assertEqual(405, S.next_transition(S.parse_minutes("23:00"), on, off))

    def test_next_transition_epoch_lands_on_the_wall_clock_edge(self):
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        now = ts_at("2026-09-13 05:00")
        nxt = sched.next_change_ts(now, TZ)
        self.assertAlmostEqual(ts_at("2026-09-13 06:45"), nxt, delta=1)
        # after the morning edge the next one is the evening edge, same day
        now = ts_at("2026-09-13 07:00")
        self.assertAlmostEqual(ts_at("2026-09-13 17:30"), sched.next_change_ts(now, TZ), delta=1)
        # after both edges it wraps to tomorrow morning
        now = ts_at("2026-09-13 20:00")
        self.assertAlmostEqual(ts_at("2026-09-14 06:45"), sched.next_change_ts(now, TZ), delta=1)

    def test_wants_on_follows_the_host_clock(self):
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        self.assertFalse(sched.wants_on(ts_at("2026-09-13 06:00"), TZ))
        self.assertTrue(sched.wants_on(ts_at("2026-09-13 09:00"), TZ))
        self.assertFalse(sched.wants_on(ts_at("2026-09-13 18:00"), TZ))

    def test_local_minutes_and_zone_label(self):
        now = ts_at("2026-09-13 17:30")
        self.assertEqual(1050, S.local_minutes(now, TZ))
        self.assertEqual("UTC+12:00", S.local_zone_name(now, TZ))


class DescribeTest(unittest.TestCase):
    def test_describes_the_example_window_in_plain_language(self):
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        text = sched.describe(ts_at("2026-09-13 05:00"), TZ)
        self.assertIn("Turns ON at 6:45 AM", text)
        self.assertIn("OFF at 5:30 PM daily", text)
        self.assertIn("Wants OFF now", text)
        self.assertIn("next change is ON at 6:45 AM", text)
        self.assertIn("in 1h", text)

    def test_describes_the_inside_of_the_window(self):
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        text = sched.describe(ts_at("2026-09-13 09:00"), TZ)
        self.assertIn("Wants ON now", text)
        self.assertIn("next change is OFF at 5:30 PM", text)

    def test_disabled_schedule_says_so(self):
        sched = S.Schedule(enabled=False, on_time="06:45", off_time="17:30")
        text = sched.describe(ts_at("2026-09-13 09:00"), TZ)
        self.assertIn("Automatic ON/OFF is off", text)
        self.assertNotIn("next change", text)

    def test_override_mentions_when_the_window_resumes(self):
        now = ts_at("2026-09-13 09:00")
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        paused = S.with_override(sched, now, TZ)
        self.assertTrue(paused.is_overridden(now))
        text = paused.describe(now, TZ)
        self.assertIn("Paused by a manual ON/OFF until 5:30 PM", text)
        self.assertIn("resumes by itself", text)

    def test_override_expires_at_the_next_boundary(self):
        now = ts_at("2026-09-13 09:00")
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        paused = S.with_override(sched, now, TZ)
        self.assertAlmostEqual(ts_at("2026-09-13 17:30"), paused.override_until, delta=1)
        later = ts_at("2026-09-13 17:31")
        self.assertFalse(paused.is_overridden(later))
        self.assertEqual(0.0, S.resume(paused).override_until)


class DueActionTest(unittest.TestCase):
    """The serve-loop seam: what must be pushed right now, if anything."""

    def setUp(self):
        self.sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        self.state = S.ScheduleState()

    def test_first_run_inside_the_window_turns_on(self):
        now = ts_at("2026-09-13 09:00")
        self.assertEqual("on", S.due_action(self.sched, self.state, now, TZ))

    def test_first_run_outside_the_window_turns_off(self):
        now = ts_at("2026-09-13 20:00")
        self.assertEqual("off", S.due_action(self.sched, self.state, now, TZ))

    def test_does_not_reflip_every_tick(self):
        now = ts_at("2026-09-13 06:45")
        self.assertEqual("on", S.due_action(self.sched, self.state, now, TZ))
        S.note_attempt(self.state, "on", True, "ok", now)
        for minute in range(1, 600):
            tick = ts_at("2026-09-13 06:45") + minute * 60
            if tick >= ts_at("2026-09-13 17:30"):
                break
            self.assertIsNone(S.due_action(self.sched, self.state, tick, TZ), minute)

    def test_flips_at_each_boundary(self):
        morning = ts_at("2026-09-13 06:44")
        S.note_attempt(self.state, "off", True, "ok", morning)
        self.state.applied = False
        self.assertIsNone(S.due_action(self.sched, self.state, morning, TZ))
        edge = ts_at("2026-09-13 06:45")
        self.assertEqual("on", S.due_action(self.sched, self.state, edge, TZ))
        S.note_attempt(self.state, "on", True, "ok", edge)
        evening = ts_at("2026-09-13 17:30")
        self.assertEqual("off", S.due_action(self.sched, self.state, evening, TZ))

    def test_disabled_window_never_acts(self):
        now = ts_at("2026-09-13 09:00")
        off = S.Schedule(enabled=False, on_time="06:45", off_time="17:30")
        self.assertIsNone(S.due_action(off, self.state, now, TZ))

    def test_manual_override_pauses_the_window_until_the_boundary(self):
        now = ts_at("2026-09-13 09:00")
        self.state.applied = True
        paused = S.with_override(self.sched, now, TZ)
        # Window wants ON and we already pushed ON; a hand OFF must not be undone.
        self.assertIsNone(S.due_action(paused, self.state, now, TZ, actual_on=False))
        self.assertIsNone(S.due_action(paused, self.state, ts_at("2026-09-13 17:29"), TZ, actual_on=False))
        # Once the boundary passes, the window is in charge again.
        after = ts_at("2026-09-13 17:30")
        self.assertEqual("off", S.due_action(paused, self.state, after, TZ))

    def test_reapplies_when_the_charger_disagrees_but_not_immediately(self):
        now = ts_at("2026-09-13 09:00")
        self.state.applied = True
        S.note_attempt(self.state, "on", True, "ok", now)
        # Something else turned it off 30 s ago: wait for the re-apply interval.
        self.assertIsNone(S.due_action(self.sched, self.state, now + 30, TZ, actual_on=False))
        self.assertIsNone(S.due_action(self.sched, self.state, now + S.REAPPLY_S - 1, TZ, actual_on=False))
        self.assertEqual("on", S.due_action(self.sched, self.state, now + S.REAPPLY_S, TZ, actual_on=False))

    def test_no_reapply_when_the_charger_agrees(self):
        now = ts_at("2026-09-13 09:00")
        self.state.applied = True
        S.note_attempt(self.state, "on", True, "ok", now)
        self.assertIsNone(S.due_action(self.sched, self.state, now + 4 * S.REAPPLY_S, TZ, actual_on=True))

    def test_failed_push_is_retried_on_the_next_interval(self):
        now = ts_at("2026-09-13 06:45")
        self.assertEqual("on", S.due_action(self.sched, self.state, now, TZ))
        S.note_attempt(self.state, "on", False, "GATT write refused", now)
        self.assertIsNone(self.state.applied)  # a failed write is not an applied state
        self.assertEqual("on", S.due_action(self.sched, self.state, now + S.CHECK_S, TZ))
        self.assertIn("GATT write refused", self.state.last_message)

    def test_config_change_is_picked_up_without_a_restart(self):
        now = ts_at("2026-09-13 09:00")
        self.state.applied = True
        self.assertIsNone(S.due_action(self.sched, self.state, now, TZ))
        narrowed = S.Schedule(enabled=True, on_time="06:45", off_time="08:00")
        self.assertEqual("off", S.due_action(narrowed, self.state, now, TZ))


class PersistenceTest(unittest.TestCase):
    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmpdir.name)
        self.path = self.tmp / "devices.json"

    def tearDown(self):
        self._tmpdir.cleanup()

    def test_defaults_when_nothing_is_configured(self):
        sched = S.load_schedule(cfg={}, env={}, path=self.path)
        self.assertTrue(sched.enabled)
        self.assertEqual("06:45", sched.on_time)
        self.assertEqual("17:30", sched.off_time)
        self.assertEqual("default", sched.source)
        self.assertEqual(0.0, sched.override_until)

    def test_reads_the_schedule_object_from_devices_json(self):
        self.path.write_text(json.dumps({"mac": "AA:BB:CC:DD:EE:FF", "keys": {}, "schedule": {"enabled": True, "on": "07:00", "off": "19:15"}}))
        sched = S.load_schedule(cfg=None, env={}, path=self.path)
        self.assertEqual("07:00", sched.on_time)
        self.assertEqual("19:15", sched.off_time)
        self.assertEqual("file", sched.source)

    def test_missing_file_is_not_an_error(self):
        sched = S.load_schedule(cfg=None, env={}, path=self.tmp / "nope.json")
        self.assertEqual("06:45", sched.on_time)

    def test_corrupt_file_falls_back_to_defaults(self):
        self.path.write_text("{not json")
        sched = S.load_schedule(cfg=None, env={}, path=self.path)
        self.assertEqual(S.DEFAULT_ON, sched.on_time)

    def test_invalid_stored_times_fall_back_to_defaults(self):
        sched = S.schedule_from_mapping({"enabled": True, "on": "25:99", "off": ""}, "file")
        self.assertEqual(S.DEFAULT_ON, sched.on_time)
        self.assertEqual(S.DEFAULT_OFF, sched.off_time)

    def test_unrecognised_stored_flag_does_not_silently_disable_the_window(self):
        sched = S.schedule_from_mapping({"enabled": "sometimes", "on": "06:45", "off": "17:30"}, "file")
        self.assertTrue(sched.enabled)
        self.assertEqual("06:45", sched.on_time)

    def test_save_roundtrips_and_keeps_mac_and_keys(self):
        self.path.write_text(json.dumps({"mac": "AA:BB:CC:DD:EE:FF", "keys": {"AA:BB:CC:DD:EE:FF": "0" * 32}}))
        saved = S.save_schedule(S.Schedule(enabled=True, on_time="06:15", off_time="18:45"), self.path)
        data = json.loads(self.path.read_text())
        self.assertEqual("AA:BB:CC:DD:EE:FF", data["mac"])
        self.assertEqual({"AA:BB:CC:DD:EE:FF": "0" * 32}, data["keys"])
        self.assertEqual({"enabled": True, "on": "06:15", "off": "18:45", "override_until": 0}, data["schedule"])
        self.assertEqual("http", saved.source)
        reloaded = S.load_schedule(cfg=None, env={}, path=self.path)
        self.assertEqual("06:15", reloaded.on_time)
        self.assertEqual("18:45", reloaded.off_time)

    def test_save_creates_the_config_dir_and_is_owner_only(self):
        nested = self.tmp / "config" / "mppt" / "devices.json"
        S.save_schedule(S.Schedule(), nested)
        self.assertTrue(nested.is_file())
        self.assertEqual(0o600, nested.stat().st_mode & 0o777)

    def test_expired_override_is_dropped_on_load(self):
        self.path.write_text(json.dumps({"schedule": {"enabled": True, "on": "06:45", "off": "17:30", "override_until": int(time.time()) - 3600}}))
        sched = S.load_schedule(cfg=None, env={}, path=self.path)
        self.assertEqual(0.0, sched.override_until)

    def test_live_override_survives_a_restart(self):
        until = int(time.time()) + 3600
        self.path.write_text(json.dumps({"schedule": {"enabled": True, "on": "06:45", "off": "17:30", "override_until": until}}))
        sched = S.load_schedule(cfg=None, env={}, path=self.path)
        self.assertEqual(until, int(sched.override_until))

    def test_env_overrides_the_file(self):
        self.path.write_text(json.dumps({"schedule": {"enabled": True, "on": "06:45", "off": "17:30"}}))
        sched = S.load_schedule(cfg=None, env={"MPPT_SCHEDULE_ON": "05:30", "MPPT_SCHEDULE_OFF": "20:00"}, path=self.path)
        self.assertEqual("05:30", sched.on_time)
        self.assertEqual("20:00", sched.off_time)
        self.assertEqual("env", sched.source)

    def test_env_can_disable_enforcement(self):
        self.assertEqual(False, S.schedule_from_env({"MPPT_SCHEDULE": "off"}, S.Schedule()).enabled)
        self.assertEqual(False, S.schedule_from_env({"MPPT_SCHEDULE": "0"}, S.Schedule()).enabled)
        self.assertEqual(True, S.schedule_from_env({"MPPT_SCHEDULE": "on"}, S.Schedule(enabled=False)).enabled)

    def test_env_window_sets_both_edges(self):
        sched = S.schedule_from_env({"MPPT_SCHEDULE": "06:45-17:30"}, S.Schedule())
        self.assertEqual("06:45", sched.on_time)
        self.assertEqual("17:30", sched.off_time)
        self.assertTrue(sched.enabled)
        both = S.schedule_from_env({"MPPT_SCHEDULE_WINDOW": "7:00-19:00"}, S.Schedule())
        self.assertEqual(("07:00", "19:00"), (both.on_time, both.off_time))

    def test_invalid_env_is_ignored_with_the_previous_value_kept(self):
        base = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        sched = S.schedule_from_env({"MPPT_SCHEDULE_ON": "banana", "MPPT_SCHEDULE": "nonsense"}, base)
        self.assertEqual("06:45", sched.on_time)
        self.assertEqual("17:30", sched.off_time)
        self.assertTrue(sched.enabled)

    def test_no_env_leaves_the_schedule_untouched(self):
        base = S.Schedule(enabled=True, on_time="07:00", off_time="19:00", source="file")
        self.assertIs(base, S.schedule_from_env({}, base))


class ParseRequestTest(unittest.TestCase):
    def setUp(self):
        self.current = S.Schedule(enabled=True, on_time="06:45", off_time="17:30", override_until=time.time() + 600)

    def test_saves_the_issue_example(self):
        sched, err = S.parse_request({"enabled": True, "on": "06:45", "off": "17:30"}, self.current)
        self.assertIsNone(err)
        self.assertEqual(("06:45", "17:30"), (sched.on_time, sched.off_time))
        self.assertTrue(sched.enabled)

    def test_saving_clears_a_manual_override(self):
        sched, err = S.parse_request({"enabled": True, "on": "06:45", "off": "17:30"}, self.current)
        self.assertIsNone(err)
        self.assertEqual(0.0, sched.override_until)

    def test_omitted_edges_keep_the_configured_values(self):
        sched, err = S.parse_request({"enabled": False}, self.current)
        self.assertIsNone(err)
        self.assertFalse(sched.enabled)
        self.assertEqual("06:45", sched.on_time)
        self.assertEqual("17:30", sched.off_time)

    def test_accepts_the_android_enable_disable_spelling(self):
        sched, err = S.parse_request({"enabled": True, "enable": "07:00", "disable": "18:30"}, self.current)
        self.assertIsNone(err)
        self.assertEqual(("07:00", "18:30"), (sched.on_time, sched.off_time))

    def test_normalises_single_digit_hours(self):
        sched, err = S.parse_request({"on": "6:45", "off": "5:30"}, self.current)
        self.assertIsNone(err)
        self.assertEqual(("06:45", "05:30"), (sched.on_time, sched.off_time))

    def test_rejects_bad_bodies(self):
        for body in ["{}", None, 42, []]:
            sched, err = S.parse_request(body, self.current)
            self.assertIsNone(sched)
            self.assertIn("body must be JSON", err)

    def test_rejects_bad_times_and_flags(self):
        sched, err = S.parse_request({"on": "25:00"}, self.current)
        self.assertIsNone(sched)
        self.assertIn("on must look like 06:45", err)
        sched, err = S.parse_request({"off": "teatime"}, self.current)
        self.assertIsNone(sched)
        self.assertIn("off must look like 17:30", err)
        sched, err = S.parse_request({"enabled": "sometimes"}, self.current)
        self.assertIsNone(sched)
        self.assertEqual("enabled must be true or false", err)

    def test_accepts_string_flags_from_a_form_post(self):
        sched, err = S.parse_request({"enabled": "true"}, self.current)
        self.assertIsNone(err)
        self.assertTrue(sched.enabled)
        sched, err = S.parse_request({"enabled": "off"}, self.current)
        self.assertIsNone(err)
        self.assertFalse(sched.enabled)


class WatchdogGatingTest(unittest.TestCase):
    """A pulse always ends with the charger ON, so it must not fight the window."""

    def setUp(self):
        self.sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        self.day = ts_at("2026-09-13 09:00")
        self.night = ts_at("2026-09-13 20:00")

    def test_allowed_inside_the_window(self):
        self.assertTrue(S.allows_pulse(self.sched, self.day, actual_on=True, tz=TZ))
        self.assertIsNone(S.pulse_blocked_reason(self.sched, self.day, actual_on=True, tz=TZ))

    def test_blocked_outside_the_window(self):
        self.assertFalse(S.allows_pulse(self.sched, self.night, actual_on=True, tz=TZ))
        self.assertIn("automatic OFF until 6:45 AM", S.pulse_blocked_reason(self.sched, self.night, actual_on=True, tz=TZ))

    def test_disabled_window_leaves_the_watchdog_alone(self):
        off = S.Schedule(enabled=False, on_time="06:45", off_time="17:30")
        self.assertTrue(S.allows_pulse(off, self.night, actual_on=True, tz=TZ))
        self.assertTrue(S.allows_pulse(off, self.night, actual_on=False, tz=TZ))

    def test_manual_off_blocks_pulsing_until_the_boundary(self):
        paused = S.with_override(self.sched, self.day, TZ)
        self.assertFalse(S.allows_pulse(paused, self.day, actual_on=False, tz=TZ))
        reason = S.pulse_blocked_reason(paused, self.day, actual_on=False, tz=TZ)
        self.assertIn("manual ON/OFF has the charger off until 5:30 PM", reason)

    def test_manual_on_still_allows_pulsing(self):
        paused = S.with_override(self.sched, self.night, TZ)
        self.assertTrue(S.allows_pulse(paused, self.night, actual_on=True, tz=TZ))

    def test_unknown_mode_does_not_disable_the_watchdog(self):
        paused = S.with_override(self.sched, self.day, TZ)
        self.assertTrue(S.allows_pulse(paused, self.day, actual_on=None, tz=TZ))


class CoerceFlagTest(unittest.TestCase):
    def test_accepts_the_usual_spellings(self):
        for value in [True, "true", "TRUE", "on", "yes", "1", 1]:
            self.assertIs(True, S.coerce_flag(value), value)
        for value in [False, "false", "off", "no", "0", 0]:
            self.assertIs(False, S.coerce_flag(value), value)

    def test_refuses_to_guess(self):
        for value in ["sometimes", "", None, 2, 1.5, [], {}]:
            self.assertIsNone(S.coerce_flag(value), value)


class StatusPayloadTest(unittest.TestCase):
    def test_payload_carries_everything_the_page_renders(self):
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        state = S.ScheduleState()
        now = ts_at("2026-09-13 05:00")
        payload = S.status_payload(sched, state, now, TZ)
        self.assertTrue(payload["enabled"])
        self.assertEqual("06:45", payload["onTime"])
        self.assertEqual("17:30", payload["offTime"])
        self.assertEqual("6:45 AM", payload["onTimeText"])
        self.assertEqual("5:30 PM", payload["offTimeText"])
        self.assertFalse(payload["wantsOn"])
        self.assertEqual("06:45", payload["nextTransition"])
        self.assertEqual("ON at 6:45 AM", payload["nextTransitionText"])
        self.assertEqual(6300, payload["nextTransitionInS"])
        self.assertFalse(payload["overridden"])
        self.assertEqual("UTC+12:00", payload["zone"])
        self.assertIn("Turns ON at 6:45 AM", payload["summary"])
        self.assertIsNone(payload["lastAction"])
        self.assertIsNone(payload["lastActionAt"])
        self.assertIsNone(payload["appliedOn"])
        json.dumps(payload)  # must stay JSON-serialisable

    def test_payload_next_change_is_off_while_inside_the_window(self):
        sched = S.Schedule(enabled=True, on_time="06:45", off_time="17:30")
        payload = S.status_payload(sched, S.ScheduleState(), ts_at("2026-09-13 09:00"), TZ)
        self.assertTrue(payload["wantsOn"])
        self.assertEqual("OFF at 5:30 PM", payload["nextTransitionText"])
        self.assertEqual("17:30", payload["nextTransition"])

    def test_payload_reports_an_active_override_and_the_last_push(self):
        now = ts_at("2026-09-13 09:00")
        sched = S.with_override(S.Schedule(enabled=True, on_time="06:45", off_time="17:30"), now, TZ)
        state = S.ScheduleState()
        S.note_attempt(state, "off", True, "mode 0", now)
        payload = S.status_payload(sched, state, now, TZ)
        self.assertTrue(payload["overridden"])
        self.assertEqual(int(sched.override_until), payload["overrideUntil"])
        self.assertEqual("off", payload["lastAction"])
        self.assertTrue(payload["lastOk"])
        self.assertEqual("mode 0", payload["lastMessage"])
        self.assertFalse(payload["appliedOn"])
        self.assertIn("Paused by a manual ON/OFF", payload["summary"])

    def test_payload_of_a_disabled_window(self):
        sched = S.Schedule(enabled=False, on_time="06:45", off_time="17:30")
        payload = S.status_payload(sched, S.ScheduleState(), ts_at("2026-09-13 09:00"), TZ)
        self.assertFalse(payload["enabled"])
        self.assertIn("Automatic ON/OFF is off", payload["summary"])


if __name__ == "__main__":
    unittest.main()
