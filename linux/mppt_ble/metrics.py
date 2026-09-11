"""Prometheus text for Instant Readout rows."""

from __future__ import annotations

CHARGE_NUM = {"OFF": 0, "BULK": 3, "ABSORPTION": 4, "FLOAT": 5}


def render_metrics(rows: list[dict]) -> str:
    lines = [
        "# HELP mppt_exporter_up Linux charger-control exporter is up",
        "# TYPE mppt_exporter_up gauge",
        "mppt_exporter_up 1",
        "# HELP victron_devices_total Number of Victron devices with a fresh Instant Readout",
        "# TYPE victron_devices_total gauge",
        f"victron_devices_total {len(rows)}",
        "# HELP victron_up Instant Readout is fresh",
        "# TYPE victron_up gauge",
        "# HELP victron_solar_power_watts Instant Readout PV power",
        "# TYPE victron_solar_power_watts gauge",
        "# HELP victron_battery_voltage_volts Instant Readout battery voltage",
        "# TYPE victron_battery_voltage_volts gauge",
        "# HELP victron_battery_current_amps Instant Readout battery current",
        "# TYPE victron_battery_current_amps gauge",
        "# HELP victron_yield_today_wh Yield today",
        "# TYPE victron_yield_today_wh gauge",
        "# HELP victron_charge_state Charge state (3 bulk 4 absorption 5 float)",
        "# TYPE victron_charge_state gauge",
        "# HELP victron_rssi_dbm Advertisement RSSI",
        "# TYPE victron_rssi_dbm gauge",
    ]
    for row in rows:
        model = f"Victron-0x{int(row.get('model_id') or 0):X}"
        dtype = row.get("device_type") or "mppt"
        labels = f'device="{model}",mac="{row["mac"]}",type="{dtype}"'
        lines.append(f"victron_up{{{labels}}} 1")
        mapping = [
            ("victron_solar_power_watts", row.get("solar_power_w")),
            ("victron_battery_voltage_volts", row.get("battery_voltage")),
            ("victron_battery_current_amps", row.get("battery_current")),
            ("victron_yield_today_wh", row.get("yield_today_wh")),
            ("victron_rssi_dbm", row.get("rssi")),
        ]
        for name, val in mapping:
            if val is not None:
                lines.append(f"{name}{{{labels}}} {val}")
        st = row.get("charge_state")
        if isinstance(st, str) and st in CHARGE_NUM:
            lines.append(f"victron_charge_state{{{labels}}} {CHARGE_NUM[st]}")
    return "\n".join(lines) + "\n"
