# Linux MPPT BLE control

Host service for a Linux box sitting next to a Victron SmartSolar.
It uses BlueZ (via [bleak](https://github.com/hbldh/bleak)).

**Not Docker.** BLE on Linux is the host BlueZ daemon + D-Bus.

Do **not** put site hostnames, tunnel tokens, remote secrets, Bluetooth PINs, or device MACs in git. Those live in `~/.config/mppt/` (see `secrets.env.example`).

If this laptop dies: **[Disaster recovery](../docs/disaster-recovery.md)** — git-backed vs laptop-only, ordered restore for https://mppt.lak.nz.

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

## Charger schedule

`serve` runs the charger off the array, not the clock. It persists in
`~/.config/mppt/devices.json` under `schedule` (mode 600):

```json
"schedule": {"enabled": true, "wake_frac": 0.5, "sleep_frac": 0.05}
```

Edit the two fractions from the **Sun rules** section of the **Charger schedule**
card on `GET /charger`, or with the JSON API
(same `X-Remote-Secret` as every other `/charger*` route):

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" \
  http://127.0.0.1:5338/charger/schedule
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:5338/charger/schedule \
  -d '{"enabled":true,"wakeFrac":0.5,"sleepFrac":0.05}'
```

A manual Enable/Disable parks the schedule until the sun changes its mind, then
the schedule re-asserts itself. The enforcer reads before it writes (falling back
to a blind idempotent apply when the mode register is not echoed), re-verifies
every 10 minutes, re-applies after a restart, and backs off 2–15 min on BLE
failure; a state flip retries immediately. `/charger/status` includes the live
schedule (`desiredOn`, learned levels, latches, override, last error).

The schedule has no clock: the charger follows the array. It turns ON once the
`0xEDBB` panel voltage reaches the wake level and OFF once output has collapsed
*and* the panel voltage is below that level — a full battery also reads ~0 W at
noon, so watts alone are not a sunset. Both levels are learned from this array's
own history (`days` table in `~/.config/mppt/watchdog.sqlite`): the wake level is
`pv_night + wake_frac × (pv_max − pv_night)` over the last 7 days and the sleep
level is `sleep_frac × today's peak output`. Edit the two fractions in the **Sun
rules** section of the Charger schedule card (or POST the same values to
`/charger/schedule`); the card's **Learned** section shows the numbers they come
from. Values are fractions 0–1, stored as `schedule.wake_frac`/`schedule.sleep_frac`
in `~/.config/mppt/devices.json`. Before a day of history exists the wake level
falls back to `2 × bus` (`0xEDEF`). Both decisions latch for the local day,
sunrise clears a sunset latch, and with no panel reading at all the charger is
left as it is.

Full operator notes (rules, status/log lines, deploy):
[`docs/pv-charger-schedule.md`](../docs/pv-charger-schedule.md).

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
