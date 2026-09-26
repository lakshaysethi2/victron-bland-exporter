# PV charger schedule (Linux)

How the downstairs MPPT host decides when the SmartSolar is on. There is **no
clock** in this rule: the charger follows the array. Edited from the **Charger
schedule** card on `GET /charger`.

## The rules

| | Trigger |
|---|---|
| **ON** | panel voltage (GATT `0xEDBB`, fresh ≤ 30 s) reaches the wake level |
| **OFF** | panel voltage is below the wake level **and** output has collapsed |

The conjunction matters. A full battery also reads ~0 W at noon, and that is not
a sunset — the panel voltage has to agree. Missing output counts as collapsed,
because a sleeping unit reports nothing.

Both levels are **learned from this array's own history**, never hardcoded volts
or watts. `mppt_ble` writes per-day extremes to `days` in
`~/.config/mppt/watchdog.sqlite` (30-day retention), and the controller reads the
last 7 days:

| | Formula | Knob |
|---|---|---|
| Wake level | `pv_night + wake_frac × (pv_max − pv_night)` | `wakeFrac` (default 0.5) |
| Sleep level | `sleep_frac × today's peak output` | `sleepFrac` (default 0.05) |

So a 231 V array with a 3 V night floor wakes at 117 V at 50%, and the level
follows the season, a re-stringed array, or a failing panel on its own. Until a
day of history exists the wake level falls back to `2 × bus` (`0xEDEF` system
voltage) — the same "panel must clear the bus by 2×" rule the pulse rules use —
and "sun down" falls back to panel below the bus.

Latch behaviour per local day:

- A sunset latch takes the charger OFF until sunrise; **sunrise clears it**, so a
  restart after dark cannot wedge a whole day off, and a dense cloud band that
  tripped the rule un-trips itself when the sun returns.
- If there is no panel reading at all and nothing has latched, the schedule has
  no opinion and leaves the charger exactly as it is — a BLE outage is not
  evidence of anything, and there is nothing we could do about it anyway.
- A manual on/off from the card parks the schedule until the sun changes its
  mind.

## Editing

Use the card, or the same authenticated endpoint:

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" \
  http://127.0.0.1:5338/charger/schedule
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:5338/charger/schedule \
  -d '{"enabled":true,"wakeFrac":0.5,"sleepFrac":0.05}'
```

`wakeFrac`/`sleepFrac` are fractions (0–1); the card shows them as percentages.
Persisted as `schedule` in `~/.config/mppt/devices.json` (atomic, mode 600).
Defaults live in `linux/mppt_ble/schedule.py` (`DEFAULT_WAKE_FRAC`,
`DEFAULT_SLEEP_FRAC`). A UI save rebuilds the gates immediately — no restart.

## What the card tells you

- **Live server clock** with the host timezone (`17:48:03 NZST`).
- **State pill** — `On · sun up`, `Off · sun down`, `Holding` (no reading yet),
  `On/Off · manual`, or `Manual control`, each with the reason the decision was
  made.
- **Learned** — the array's floor and peak, the span, days of history, the
  resulting wake/sleep levels, and the live panel/bus/output values behind them.
- **Save** is enabled only when the form differs from the server.

## Status & logs

`GET /charger/status` → `schedule` carries `wakeFrac`/`sleepFrac`, the `learned`
block (`pvMax`, `pvNight`, `wattsPeakToday`, `wakeV`, `sleepW`, `days`,
`bootstrap`), the live `panelV`/`busV`/`watts` used, the latches, `reason`, and
`override`. `GET /charger/schedule` returns the same snapshot.

Log lines: `schedule: sun up: panel 212V ≥ 117V — charger ON`,
`schedule: sun down: panel 8V, 12W < 70W — charger OFF`,
`schedule: sun changed its mind (...)`, `schedule: no panel reading yet`.

The phone app still runs the older time-window version, so the two bridges
disagree while it is not being developed.
