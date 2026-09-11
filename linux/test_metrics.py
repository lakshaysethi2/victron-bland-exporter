from mppt_ble.metrics import render_metrics


def test_metrics_include_power_and_voltage():
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
    assert "victron_solar_power_watts" in body
    assert "120.0" in body
    assert "victron_battery_voltage_volts" in body
    assert "victron_charge_state" in body
    assert "victron_devices_total 1" in body
