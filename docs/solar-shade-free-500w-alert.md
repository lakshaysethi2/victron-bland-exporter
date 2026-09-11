# Midday low watts → local charger pulse (Linux)

Some people run the exporter on a Linux host beside the SmartSolar instead of on Android.

That host already sees Instant Readout watts. `python -m mppt_ble serve` can watch those watts in-process. If they stay under a configured floor in the midday window (host local clock), it turns the charger OFF for a few seconds, then ON. Same if watts look stuck after a cloud. It must never leave the charger off.

Dashboards can scrape `/metrics`. They do not need to trigger the pulse.

Secrets stay in a local env file (`~/.config/mppt/secrets.env`), not in git.
