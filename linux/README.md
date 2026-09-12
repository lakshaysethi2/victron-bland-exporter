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
