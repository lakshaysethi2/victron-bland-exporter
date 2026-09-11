# Midday low watts → laptop pulses the charger

Grafana only *shows* the problem. It does not fix it.

The Linux laptop next to the MPPT already sees Instant Readout watts on `http://127.0.0.1:5338/metrics`.
`python -m mppt_ble.yield_reset` watches that local page. If watts stay under 500 W from 11:30–15:30 (laptop local clock) for about two minutes, it turns the charger OFF for 4 seconds, then ON. Same if watts look stuck after a cloud. Never leaves the charger off.

Enable `linux/mppt-yield-reset.service` on the laptop. Secrets stay in `~/.config/mppt/secrets.env`.
