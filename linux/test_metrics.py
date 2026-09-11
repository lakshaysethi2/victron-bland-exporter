import unittest

from mppt_ble.metrics import render_metrics


class MetricsTest(unittest.TestCase):
    def test_metrics_include_help_and_series(self):
        body = render_metrics(
            [
                {
                    "mac": "AA:BB:CC:DD:EE:FF",
                    "model_id": 0xA116,
                    "device_type": "mppt",
                    "solar_power_w": 120.0,
                    "battery_voltage": 13.2,
                    "battery_current": 1.5,
                    "yield_today_wh": 400,
                    "rssi": -70,
                    "charge_state": "BULK",
                }
            ]
        )
        self.assertIn("# HELP", body)
        self.assertIn("# TYPE", body)
        self.assertIn("victron_up", body)
        self.assertIn("victron_solar_power_watts", body)
        self.assertIn("victron_battery_voltage_volts", body)
        self.assertIn("victron_charge_state", body)
        self.assertIn("victron_devices_total 1", body)


if __name__ == "__main__":
    unittest.main()
