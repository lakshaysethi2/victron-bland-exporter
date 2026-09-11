# Midday low watts → local charger pulse (Linux)

Some people run the exporter on a Linux host beside the SmartSolar instead of on Android.

That host already sees Instant Readout watts and GATT panel voltage. `python -m mppt_ble serve` watches those in-process. By default it only pulses when panel voltage is high (~Voc) while the battery still wants charge and watts are too low (local MPPT peak). Watt-drop / midday-shade pulses stay off unless `watt_only_pulses` is true. It must never leave the charger off.

Dashboards can scrape `/metrics`. They do not need to trigger the pulse.

Secrets stay in a local env file (`~/.config/mppt/secrets.env`), not in git.
