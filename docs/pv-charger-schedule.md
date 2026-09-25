# PV charger schedule (Linux)

How the downstairs MPPT host decides when the SmartSolar is on. Two layers,
edited from the **Charger schedule** card on `GET /charger`:

1. **Daily window** — the guaranteed one. Charger is ON between the two times
   every day, whatever the weather (default 07:00–18:00). This is the fallback.
2. **Sunlight boost** — optional. Start early once the panel wakes up, stop
   early once output fades. It only ever *extends* the daily window; it can
   never be the only reason the charger is on.

## The rules

| | Default | Trigger |
|---|---|---|
| Early start | after 05:00, panel ≥ 60 V | panel voltage (GATT `0xEDBB`, fresh ≤ 30 s) |
| Early stop | after 17:00, output < 40 W | Instant Readout watts (fresh ≤ 90 s) |

Both decisions latch for the local day and reset at midnight. A missing or
stale reading never latches, so a lost BLE link cannot strand the charger off —
the daily window still runs.

Ignored for overnight (`enable > disable`) and 24 h (`enable == disable`)
windows, where the window itself is the intent.

## Editing

Use the card, or the same authenticated endpoint:

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" \
  http://127.0.0.1:5338/charger/schedule
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:5338/charger/schedule \
  -d '{"enabled":true,"enableTime":"07:00","disableTime":"18:00",
       "pv":{"enabled":true,"wakeAfter":"05:00","wakePanelV":60,
             "sleepAfter":"17:00","sleepWatts":40}}'
```

Persisted as `schedule.pv` in `~/.config/mppt/devices.json` (atomic, mode 600),
alongside the base window. Defaults live in `linux/mppt_ble/schedule.py`
(`DEFAULT_PV_*`). A UI save rebuilds the gates immediately — no restart.

## What the card tells you

- **Live server clock** with the host timezone (`17:48:03 NZST`) — the window
  times are in that zone, not the phone's.
- **State pill** — `On · daily window`, `On · sunlight boost`,
  `Off · sunlight boost`, `Off · manual override`, or `Manual control`, each
  with a one-line reason.
- **24 h timeline** — solid green = daily window, blue hatched = the regions
  sunlight boost can extend into, white line = now.
- **Save** is enabled only when the form differs from the server.

## Status & logs

`GET /charger/status` → `schedule.pv` carries the thresholds plus
`morningStarted` / `eveningEnded` and the last `watts` / `panelV` used.
`GET /charger/schedule` returns the same snapshot.

Log lines to look for:

```
schedule: PV wake after 05:00 (panel ≥ 60.0V)
schedule: PV sleep after 17:00 (watts < 40W)
schedule: charger OFF (window 06:23-18:00)
```

A manual Enable/Disable still pauses the schedule until the next boundary, so
it does not fight a person tapping the button.

## Deploying Linux changes

The `mppt-ble` user service runs straight from this clone
(`WorkingDirectory=%h/code/victron-bland-exporter/linux`), so deploying a code
change is: pull/commit, then

```bash
systemctl --user restart mppt-ble
systemctl --user is-active mppt-ble
```

Verify with `GET /charger/status` (`schedule.pv` present) and
`curl -s http://127.0.0.1:5338/charger | grep -c "Sunlight boost"`.

## Verified

2026-09-25 17:48 NZST: output fell to 0 W after 17:00 → `PV sleep` latched →
`schedule: charger OFF`. The daily window would have kept it on until 18:00.
