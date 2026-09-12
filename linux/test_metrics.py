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
        self.assertIn("victron_panel_voltage_volts", body)
        self.assertNotIn("victron_panel_voltage_volts{", body)

    def test_panel_voltage_emitted_while_fresh(self):
        row = {
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
        now = 1_000_000.0
        body = render_metrics(
            [row],
            panel={
                "mac": "AA:BB:CC:DD:EE:FF",
                "model_id": 0xA116,
                "volts": 84.12,
                "updated_at": now - 10,
            },
            now=now,
        )
        self.assertIn(
            'victron_panel_voltage_volts{device="Victron-0xA116",mac="AA:BB:CC:DD:EE:FF",type="mppt"} 84.12',
            body,
        )

    def test_stale_or_na_panel_voltage_omitted(self):
        now = 1_000_000.0
        stale = render_metrics(
            [],
            panel={"mac": "AA:BB", "volts": 80.0, "updated_at": now - 400, "model_id": 0},
            now=now,
        )
        self.assertNotIn("victron_panel_voltage_volts{", stale)
        night = render_metrics(
            [],
            panel={"mac": "AA:BB", "volts": None, "updated_at": now, "model_id": 0},
            now=now,
        )
        self.assertNotIn("victron_panel_voltage_volts{", night)


if __name__ == "__main__":
    unittest.main()
