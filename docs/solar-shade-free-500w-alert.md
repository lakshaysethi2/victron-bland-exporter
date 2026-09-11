# Midday low watts → local charger pulse (Linux)

Some people run the exporter on a Linux host beside the SmartSolar instead of on Android.

That host already sees Instant Readout watts and GATT panel voltage. `python -m mppt_ble serve` watches those in-process. It pulses OFF then ON if watts look stuck after a cloud, if midday watts stay under the shade floor, or if panel voltage is high (~Voc) while battery still wants charge and watts are too low (local MPPT peak). It must never leave the charger off.

Dashboards can scrape `/metrics`. They do not need to trigger the pulse.

Secrets stay in a local env file (`~/.config/mppt/secrets.env`), not in git.
