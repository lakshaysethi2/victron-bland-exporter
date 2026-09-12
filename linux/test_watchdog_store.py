import tempfile
import time
import unittest
from pathlib import Path

from mppt_ble.watchdog_store import WatchdogStore
from mppt_ble.yield_reset import (
    ResetPolicy,
    ResetState,
    avg_gap,
    hydrate_state,
    ingest,
    note_pulse,
    pulses_last_hour,
    voc_gap,
)


def noon() -> float:
    return time.mktime(time.strptime("2026-01-15 12:00", "%Y-%m-%d %H:%M"))


class WatchdogStoreTest(unittest.TestCase):
    def test_survives_restart(self):
        p = ResetPolicy()
        t0 = noon()
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "watchdog.sqlite"
            store = WatchdogStore(path)
            st = ResetState(store=store)
            for i in range(12):
                ingest(st, t0 + i * 10, 800, p, 150.0, 40.0)
            note_pulse(st, t0 + 120)
            self.assertEqual(12, len(st.samples))
            self.assertEqual(1, pulses_last_hour(st, t0 + 120))

            st2 = ResetState()
            n = hydrate_state(st2, WatchdogStore(path), t0 + 130)
            self.assertEqual(12, n)
            self.assertEqual(1, pulses_last_hour(st2, t0 + 130))
            self.assertAlmostEqual(110.0, voc_gap(st2, t0 + 130, p), delta=0.1)
            self.assertAlmostEqual(110.0, avg_gap(st2, t0 + 130, p), delta=0.1)
            self.assertEqual(t0 + 120, st2.last_pulse_at)
            self.assertIsNone(st2.local_mpp_since)


if __name__ == "__main__":
    unittest.main()
