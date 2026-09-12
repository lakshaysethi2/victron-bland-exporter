import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from mppt_ble.yield_reset import (
    ResetPolicy,
    ResetState,
    classify_weather,
    clear_sky_watts,
    ingest,
    is_daytime,
    load_policy,
    local_mpp_stuck,
    note_pulse,
    pulse_why,
    should_pulse,
    voltage_delta,
)


def noon() -> float:
    return time.mktime(time.strptime("2026-01-15 12:00", "%Y-%m-%d %H:%M"))


def fill(st: ResetState, p: ResetPolicy, t0: float, series, step: float = 10.0) -> float:
    ts = t0
    for watts, pv, out in series:
        ingest(st, ts, watts, p, pv, out)
        ts += step
    return ts - step


class YieldResetTest(unittest.TestCase):
    def test_clear_sky_noon_is_1600(self):
        p = ResetPolicy()
        self.assertAlmostEqual(1600, clear_sky_watts(noon(), p), delta=1)

    def test_weather_classes(self):
        p = ResetPolicy()
        self.assertEqual("bright", classify_weather(1500, 1600, p)[0])
        regime, expected = classify_weather(950, 1600, p)
        self.assertEqual("partly", regime)
        self.assertGreaterEqual(expected, 900)
        self.assertLessEqual(expected, 1100)
        self.assertEqual("overcast", classify_weather(500, 1600, p)[0])

    def test_overcast_steady_does_not_pulse(self):
        p = ResetPolicy(hold_s=10, min_peak_w=50, watt_only_pulses=True)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 500, p)
        self.assertEqual("overcast", st.regime)
        self.assertFalse(should_pulse(st, t0 + 1, 480, p))
        self.assertFalse(should_pulse(st, t0 + 40, 450, p))

    def test_partly_cloudy_drop_from_peak_pulses(self):
        p = ResetPolicy(hold_s=20, min_peak_w=50, stuck_fraction=0.55, watt_only_pulses=True)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 1000, p)
        self.assertEqual("partly", st.regime)
        ingest(st, t0 + 5, 200, p)
        self.assertFalse(should_pulse(st, t0 + 5, 200, p))
        self.assertTrue(should_pulse(st, t0 + 30, 200, p))

    def test_bright_drop_pulses(self):
        p = ResetPolicy(hold_s=15, min_peak_w=50, watt_only_pulses=True)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 1580, p)
        self.assertEqual("bright", st.regime)
        ingest(st, t0 + 2, 300, p)
        self.assertFalse(should_pulse(st, t0 + 2, 300, p))
        self.assertTrue(should_pulse(st, t0 + 20, 300, p))

    def test_night_no_pulse(self):
        p = ResetPolicy()
        self.assertFalse(is_daytime(time.mktime(time.strptime("2026-01-15 02:00", "%Y-%m-%d %H:%M")), p))

    def test_midday_floor_needs_higher_peak(self):
        p = ResetPolicy(shade_hold_s=120, shade_floor_w=500, watt_only_pulses=True)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 900, p)
        self.assertFalse(should_pulse(st, t0 + 1, 200, p))
        self.assertTrue(should_pulse(st, t0 + 130, 200, p))

    def test_midday_low_without_peak_is_weather(self):
        p = ResetPolicy(shade_hold_s=10, min_peak_w=50, watt_only_pulses=True)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 400, p)
        self.assertFalse(should_pulse(st, t0 + 1, 400, p))
        self.assertFalse(should_pulse(st, t0 + 40, 380, p))

    def test_sustained_voc_gap_at_1200w_pulses(self):
        p = ResetPolicy(local_mpp_hold_s=20)
        st = ResetState()
        t0 = noon()
        end = fill(st, p, t0, [(1198, 148.9, 40.0)] * 12)
        self.assertTrue(local_mpp_stuck(1198, 148.9, 40.0, p, state=st, ts=end))
        self.assertFalse(should_pulse(st, end, 1198, p, panel_v=148.9, battery_v=40.0))
        self.assertTrue(should_pulse(st, end + 20, 1198, p, panel_v=148.9, battery_v=40.0))
        self.assertIn("local-mpp", st.pulse_reason)
        self.assertIn("avgGap", st.pulse_reason)

    def test_envelope_skips_1414w_at_noon(self):
        p = ResetPolicy()
        st = ResetState()
        t0 = noon()
        end = fill(st, p, t0, [(1414, 147.6, 40.0)] * 12)
        self.assertFalse(local_mpp_stuck(1414, 147.6, 40.0, p, state=st, ts=end))
        self.assertIn("envelope", pulse_why(1414, 147.6, 40.0, p, end, 0, st))

    def test_gap_spike_after_real_mpp_does_not_pulse(self):
        """12:39: 2 min average stayed ~101 V after 1602 W at 99 V; 2h Voc gap ~110 V."""
        p = ResetPolicy(local_mpp_hold_s=1)
        st = ResetState()
        t0 = noon()
        fill(st, p, t0, [(1200, 149.7, 40.0)] * 12)
        t1 = t0 + 300
        fill(st, p, t1, [(1602, 139.5, 40.0)] * 9 + [(1414, 147.6, 40.0)] * 3)
        ts = t1 + 11 * 10
        self.assertFalse(local_mpp_stuck(1414, 147.6, 40.0, p, state=st, ts=ts))
        self.assertFalse(should_pulse(st, ts + 1, 1414, p, panel_v=147.6, battery_v=40.0))

    def test_cascade_high_delta_low_enough_watts_pulses(self):
        p = ResetPolicy()
        st = ResetState()
        t0 = noon()
        self.assertAlmostEqual(101.3, voltage_delta(131.41, 30.11), delta=0.01)
        end = fill(st, p, t0, [(721, 131.41, 30.11)] * 12)
        self.assertTrue(local_mpp_stuck(721, 131.41, 30.11, p, state=st, ts=end))
        self.assertFalse(should_pulse(st, end, 721, p, panel_v=131.41, battery_v=30.11))
        self.assertTrue(should_pulse(st, end + 30, 721, p, panel_v=131.41, battery_v=30.11))

    def test_small_delta_does_not_pulse(self):
        p = ResetPolicy(local_mpp_hold_s=1)
        st = ResetState()
        t0 = noon()
        end = fill(st, p, t0, [(200, 125.0, 60.0)] * 12)
        self.assertFalse(local_mpp_stuck(200, 125.0, 60.0, p, state=st, ts=end))
        self.assertFalse(should_pulse(st, end + 1, 200, p, panel_v=125.0, battery_v=60.0))

    def test_local_mpp_skips_low_panel_voltage(self):
        p = ResetPolicy(local_mpp_hold_s=1)
        st = ResetState()
        t0 = noon()
        fill(st, p, t0, [(180, 90.0, 40.0)] * 12)
        self.assertFalse(should_pulse(st, t0 + 120, 180, p, panel_v=90.0, battery_v=40.0))

    def test_no_panel_voltage_does_not_pulse_blindly(self):
        p = ResetPolicy(hold_s=1, min_peak_w=50)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 1500, p)
        ingest(st, t0 + 2, 200, p)
        self.assertFalse(should_pulse(st, t0 + 40, 200, p, panel_v=None, battery_v=40.0))

    def test_local_mpp_skips_when_battery_full(self):
        p = ResetPolicy(local_mpp_hold_s=1)
        st = ResetState()
        t0 = noon()
        fill(st, p, t0, [(180, 150.0, 54.0)] * 12)
        self.assertFalse(local_mpp_stuck(180, 150.0, 54.0, p, state=st, ts=t0 + 110))
        self.assertFalse(should_pulse(st, t0 + 120, 180, p, panel_v=150.0, battery_v=54.0))

    def test_pulse_why_explains_skip_and_ready(self):
        p = ResetPolicy()
        t0 = noon()
        st = ResetState()
        fill(st, p, t0, [(180, 150.0, 40.0)] * 12)
        ts = t0 + 110
        self.assertEqual("ready", pulse_why(180, 150.0, 40.0, p, ts, 0, st))
        self.assertIn("waiting", pulse_why(180, None, 40.0, p, ts, 0))
        self.assertIn("below", pulse_why(180, 90.0, 40.0, p, ts, 0))
        self.assertIn("envelope", pulse_why(1716, 147.7, 40.0, p, ts, 0, st))

    def test_rate_limit_two_per_hour_and_15m_cooldown(self):
        p = ResetPolicy(local_mpp_hold_s=1, cooldown_s=900, local_mpp_max_per_hour=2)
        st = ResetState()
        t0 = noon()
        end = fill(st, p, t0, [(800, 150.0, 40.0)] * 12)
        self.assertFalse(should_pulse(st, end, 800, p, panel_v=150.0, battery_v=40.0))
        self.assertTrue(should_pulse(st, end + 1, 800, p, panel_v=150.0, battery_v=40.0))
        note_pulse(st, end + 1)
        t_cool = fill(st, p, end + 1 + 100, [(800, 150.0, 40.0)] * 12)
        self.assertFalse(should_pulse(st, t_cool + 1, 800, p, panel_v=150.0, battery_v=40.0))
        t_ok = fill(st, p, end + 1 + 900, [(800, 150.0, 40.0)] * 12)
        self.assertTrue(should_pulse(st, t_ok + 1, 800, p, panel_v=150.0, battery_v=40.0))
        note_pulse(st, t_ok + 1)
        t3 = fill(st, p, t_ok + 1 + 900, [(800, 150.0, 40.0)] * 12)
        self.assertFalse(should_pulse(st, t3 + 1, 800, p, panel_v=150.0, battery_v=40.0))
        self.assertIn("rate", pulse_why(800, 150.0, 40.0, p, t3 + 1, st.last_pulse_at, st))

    def test_load_config_json(self):
        raw = {
            "clear_sky_watts_by_hour": {"12": 1600, "13": 1600},
            "partly_cloudy_factor": 0.6,
            "off_s": 4,
            "cooldown_s": 300,
            "local_mpp_panel_min_v": 125,
            "local_mpp_max_w": 350,
        }
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "c.json"
            path.write_text(json.dumps(raw))
            p = load_policy(str(path))
        self.assertEqual(1600, p.clear_sky[12])
        self.assertEqual(4, p.off_s)
        self.assertEqual(300, p.cooldown_s)
        self.assertEqual(125, p.local_mpp_panel_min_v)
        self.assertEqual(350, p.local_mpp_max_w)

    def test_repo_yield_config_rate_limits_and_gap_average(self):
        path = Path(__file__).resolve().parent / "yield_config.json"
        p = load_policy(str(path))
        self.assertEqual(900, p.cooldown_s)
        self.assertEqual(4, p.local_mpp_max_per_hour)
        self.assertEqual(120, p.local_mpp_gap_avg_s)
        self.assertEqual(7200, p.local_mpp_extrema_s)
        self.assertEqual(8, p.local_mpp_gap_margin_v)
        self.assertEqual(0.85, p.local_mpp_clear_skip)

    def test_env_overrides_max_pulses_per_hour(self):
        path = Path(__file__).resolve().parent / "yield_config.json"
        with mock.patch.dict(os.environ, {"MPPT_MAX_PULSES_PER_HOUR": "4"}):
            self.assertEqual(4, load_policy(str(path)).local_mpp_max_per_hour)
        with mock.patch.dict(os.environ, {"MPPT_MAX_PULSES_PER_HOUR": "6"}):
            self.assertEqual(6, load_policy(str(path)).local_mpp_max_per_hour)
        with mock.patch.dict(os.environ, {"MPPT_MAX_PULSES_PER_HOUR": "0"}):
            self.assertEqual(4, load_policy(str(path)).local_mpp_max_per_hour)


if __name__ == "__main__":
    unittest.main()
