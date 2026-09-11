import unittest

from mppt_ble.restart import grafana_firing, wants_restart


class RestartDetectTest(unittest.TestCase):
    def test_explicit_restart(self):
        self.assertTrue(wants_restart({"action": "restart"}))
        self.assertFalse(wants_restart({"action": "on"}))

    def test_firing_payload(self):
        body = {
            "status": "firing",
            "alerts": [
                {
                    "status": "firing",
                    "labels": {"kind": "shade-free-expect", "alertname": "Solar power below 500W"},
                }
            ],
        }
        self.assertTrue(grafana_firing(body))
        self.assertTrue(wants_restart(body))

    def test_resolved_payload(self):
        body = {
            "status": "resolved",
            "alerts": [{"status": "resolved", "labels": {"kind": "shade-free-expect"}}],
        }
        self.assertFalse(grafana_firing(body))


if __name__ == "__main__":
    unittest.main()
