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
~/.venv/mppt-ble/bin/python -m mppt_ble off --mac "$MPPT_MAC"
~/.venv/mppt-ble/bin/python -m mppt_ble on --mac "$MPPT_MAC"
```

HTTP (default bind `127.0.0.1:5338`; put a tunnel in front if you want the internet):

```bash
set -a && source ~/.config/mppt/secrets.env && set +a
~/.venv/mppt-ble/bin/python -m mppt_ble serve --bind 127.0.0.1:5338
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" \
  -X POST http://127.0.0.1:5338/charger -d '{"action":"off"}'
```

`MPPT_REMOTE_SECRET` is required for `serve`. Instant Readout keys go in `~/.config/mppt/devices.json` (`{"mac":"…","keys":{"AA:BB:…":"32hex"}}`).

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
