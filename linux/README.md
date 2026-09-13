# Linux MPPT BLE control

Host service for a Linux box sitting next to a Victron SmartSolar.
It uses BlueZ (via [bleak](https://github.com/hbldh/bleak)).

**Not Docker.** BLE on Linux is the host BlueZ daemon + D-Bus.

Do **not** put site hostnames, tunnel tokens, remote secrets, Bluetooth PINs, or device MACs in git. Those live in `~/.config/mppt/` (see `secrets.env.example`).

## Why this exists

Instant Readout advertisements are read-only. Charger on/off is a GATT write to register `0x0200` on service `306b0001-…`. Some extra handshake frames (`fa80ff` on `306b0002`, long `06008218…` blobs) make this MPPT drop the link.

VictronConnect will steal the only GATT session. Close it before using this.

Pairing: the device PIN is on the sticker (not `000000` on every unit). Bond with BlueZ once, then writes work.

## Install

```bash
sudo apt-get install -y python3-venv python3-pip bluez
python3 -m venv ~/.venv/mppt-ble
~/.venv/mppt-ble/bin/pip install -r requirements.txt
sudo usermod -aG bluetooth "$USER"   # then log out/in
mkdir -p ~/.config/mppt
cp secrets.env.example ~/.config/mppt/secrets.env
chmod 600 ~/.config/mppt/secrets.env
# edit MAC, remote secret, optional public hostname
```

Scan / on / off (MAC from `MPPT_MAC` or `--mac`):

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
~/.venv/mppt-ble/bin/python -m mppt_ble scan
~/.venv/mppt-ble/bin/python -m mppt_ble read --mac "$MPPT_MAC"
~/.venv/mppt-ble/bin/python -m mppt_ble panel --mac "$MPPT_MAC"
~/.venv/mppt-ble/bin/python -m mppt_ble off --mac "$MPPT_MAC"
~/.venv/mppt-ble/bin/python -m mppt_ble on --mac "$MPPT_MAC"
```

HTTP (default bind `127.0.0.1:5338`; put a tunnel in front if you want the internet). `GET /charger` is the phone-friendly control page (secret in `X-Remote-Secret`; shows panel V, Victron out, gap, watts, pulse candidate):

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
~/.venv/mppt-ble/bin/python -m mppt_ble serve --bind 127.0.0.1:5338
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" \
  -X POST http://127.0.0.1:5338/charger -d '{"action":"off"}'
```

`MPPT_REMOTE_SECRET` is required for `serve`. Instant Readout keys go in `~/.config/mppt/devices.json` (`{"mac":"…","keys":{"AA:BB:…":"32hex"}}`).

## Automatic ON / OFF schedule

`serve` turns the charger on and off at a daily window you set, so nobody has to
remember to enable it in the morning and disable it in the evening. Times are
`HH:MM` on the **host clock** (the box beside the MPPT); the page shows the
12-hour form and the timezone it is using.

```bash
# ON at 06:45, OFF at 17:30 every day
~/.venv/mppt-ble/bin/python -m mppt_ble serve --schedule-on 06:45 --schedule-off 17:30
# hand control only
~/.venv/mppt-ble/bin/python -m mppt_ble serve --no-schedule
```

Or set it from the `/charger` page (**Automatic ON / OFF** → pick both times →
*Save window*), or with curl:

```bash
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:5338/charger/schedule -d '{"enabled":true,"on":"06:45","off":"17:30"}'
```

The window is stored under the `schedule` key of `~/.config/mppt/devices.json`
and survives a restart. Environment overrides the file (useful in a systemd
unit): `MPPT_SCHEDULE=on|off`, `MPPT_SCHEDULE_WINDOW=06:45-17:30`, or
`MPPT_SCHEDULE_ON` / `MPPT_SCHEDULE_OFF` for one edge each. CLI flags win over
both.

Rules:

- ON 06:45 / OFF 17:30 → charger ON inside `[06:45, 17:30)`.
- ON later than OFF → overnight window (ON 17:30 / OFF 06:45 spans midnight).
- ON == OFF → treated as a 24 h window, so a degenerate config never locks the
  charger off.
- A hand Enable/Disable (or a manual *Pulse cascade*, which ends ON) pauses the
  window until the next boundary, then it resumes by itself. *Resume window* on
  the page — or saving the window again — hands control back immediately. The
  pause is persisted, so a service restart does not resume early.
- The loop checks the clock every 15 s and only opens a GATT session when the
  wanted state changes. If the charger is later found disagreeing with the
  window, it is re-asserted at most every 10 minutes.
- The yield watchdog will not auto-pulse while the window (or a hand OFF) wants
  the charger off, because a pulse always ends ON. `/charger` says so in the
  *Automatic ON / OFF* note, and `GET /charger/status` reports `pulseBlocked`.

`GET /charger/status` carries the whole window as a `schedule` object
(`enabled`, `onTime`/`offTime`, `onTimeText`/`offTimeText`, `wantsOn`,
`nextTransition`, `nextTransitionTs`, `nextTransitionText`, `overridden`,
`overrideUntil`, `summary`, `zone`, `source`, `lastAction`, `lastOk`,
`appliedOn`).

`serve` polls PV panel voltage over GATT register `0xEDBB` at least every 10 seconds and exposes `victron_panel_voltage_volts` on `/metrics` while a 2-byte value is fresh (30 s). Instant Readout does not carry panel voltage. Night-time `0xFFFF` is omitted. If a poll fails, back off 20–120 s. Always GET `0xEDBB` after STREAM_ENABLE even if no `08` frame arrived yet. Do not USB-reset the adapter from that loop.

Handshake on this SmartSolar: `01` / `0300` / `f980` / `060082189342102703010303` (VictronConnect stream-enable). That last frame is what switches the radio to type-03 `08 03 19` value notifies, including `0xEDBB`. Without the trailing `03010303`, GET returns `09 00 19 ed bb 01` (unknown id, not volts). Do not send `fa80ff` or `f941` after the blob — those drop this unit.

This Victron feeds a downstream MPPT, not the cells. The watchdog pulses when the 2-minute average `Vpv − Vout` is within 0.07 × the 2-hour Voc gap of that Voc (max panel − max out), the Voc gap is at least 2 × bus, panel is at least 0.85 × 2h max panel, Victron out is 0.75–1.30 × the bus class (GATT `0xEDEF`, else 2h max out), and watts are below 0.85 × this hour’s clear-sky envelope. Each pulse costs a yield dip, so auto-pulse is limited to 15 minutes apart and 4 per hour (`local_mpp_max_per_hour` in `linux/yield_config.json`, or `MPPT_MAX_PULSES_PER_HOUR` in `~/.config/mppt/secrets.env`). Watchdog samples persist in `~/.config/mppt/watchdog.sqlite` so a restart keeps the 2 min / 2 h windows.

systemd user units: copy `mppt-ble.service` (and optionally `cloudflared-mppt.service`) to `~/.config/systemd/user/`, then `systemctl --user daemon-reload && systemctl --user enable --now mppt-ble`.

Named tunnel: point Cloudflare ingress at `http://127.0.0.1:5338`, put the token in the file named by `CLOUDFLARED_TOKEN_FILE`. Do not put the token in the unit file.

## Cloud-reset (optional)

If another MPPT (e.g. a power station) stays lazy after clouds, pulse this Victron off then on. Always re-enables. Never leave it off.

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
~/.venv/mppt-ble/bin/python -m mppt_ble.yield_reset \
  --mac "$MPPT_MAC" \
  --metrics "$MPPT_METRICS_URL" \
  --dry-run
```
