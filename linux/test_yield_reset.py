import json
import tempfile
import time
import unittest
from pathlib import Path

from mppt_ble.yield_reset import (
    ResetPolicy,
    ResetState,
    classify_weather,
    clear_sky_watts,
    ingest,
    is_daytime,
    load_policy,
    should_pulse,
)


def noon() -> float:
    return time.mktime(time.strptime("2026-01-15 12:00", "%Y-%m-%d %H:%M"))


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
        p = ResetPolicy(hold_s=10, min_peak_w=50)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 500, p)
        self.assertEqual("overcast", st.regime)
        self.assertFalse(should_pulse(st, t0 + 1, 480, p))
        self.assertFalse(should_pulse(st, t0 + 40, 450, p))

    def test_partly_cloudy_drop_from_peak_pulses(self):
        p = ResetPolicy(hold_s=20, min_peak_w=50, stuck_fraction=0.55)
        st = ResetState()
        t0 = noon()
        ingest(st, t0, 1000, p)
        self.assertEqual("partly", st.regime)
        ingest(st, t0 + 5, 200, p)
        self.assertFalse(should_pulse(st, t0 + 5, 200, p))
        self.assertTrue(should_pulse(st, t0 + 30, 200, p))

    def test_bright_drop_pulses(self):
        p = ResetPolicy(hold_s=15, min_peak_w=50)
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

    def test_load_config_json(self):
        raw = {
            "clear_sky_watts_by_hour": {"12": 1600, "13": 1600},
            "partly_cloudy_factor": 0.6,
            "off_s": 4,
        }
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "c.json"
            path.write_text(json.dumps(raw))
            p = load_policy(str(path))
        self.assertEqual(1600, p.clear_sky[12])
        self.assertEqual(4, p.off_s)


if __name__ == "__main__":
    unittest.main()
