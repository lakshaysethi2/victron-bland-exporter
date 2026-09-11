"""Prometheus text for Instant Readout rows."""

from __future__ import annotations

import time

from .protocol import PANEL_FRESH_S

CHARGE_NUM = {"OFF": 0, "BULK": 3, "ABSORPTION": 4, "FLOAT": 5}


def _labels(row: dict) -> str:
    model = f"Victron-0x{int(row.get('model_id') or 0):X}"
    dtype = row.get("device_type") or "mppt"
    return f'device="{model}",mac="{row["mac"]}",type="{dtype}"'


def panel_sample(panel: dict | None, now: float) -> tuple[str, float] | None:
    """Return (labels, volts) while the GATT read is fresh and not night-NA."""
    if not panel:
        return None
    volts = panel.get("volts")
    updated = float(panel.get("updated_at") or 0)
    if not isinstance(volts, (int, float)) or updated <= 0:
        return None
    if now - updated >= PANEL_FRESH_S:
        return None
    mac = str(panel.get("mac") or "")
    model = f"Victron-0x{int(panel.get('model_id') or 0):X}"
    labels = f'device="{model}",mac="{mac}",type="mppt"'
    return labels, float(volts)


def render_metrics(rows: list[dict], panel: dict | None = None, now: float | None = None) -> str:
    ts = time.time() if now is None else now
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
        "# HELP victron_panel_voltage_volts PV input voltage (GATT 0xEDBB)",
        "# TYPE victron_panel_voltage_volts gauge",
    ]
    for row in rows:
        labels = _labels(row)
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
    sample = panel_sample(panel, ts)
    if sample:
        labels, volts = sample
        lines.append(f"victron_panel_voltage_volts{{{labels}}} {volts}")
    return "\n".join(lines) + "\n"
