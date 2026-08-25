# Linux MPPT BLE control

Host service for the downstairs laptop sitting next to the SmartSolar.
It uses BlueZ (via [bleak](https://github.com/hbldh/bleak)), not Android GATT.

**Not Docker.** BLE on Linux is the host BlueZ daemon + D-Bus. A container
needs `--network host`, `/var/run/dbus`, and usually `--privileged`. That is
more fragile than a venv + systemd unit on the machine that already has
`hci0`.

## Why this exists

The Android app still owns Instant Readout + tunnel. Charger *writes* on this
MPPT are picky: `fa80ff` on `306b0002` and the long `06008218…` handshake make
the unit drop the link (GATT 19). BlueZ is a second stack to prove whether
register `0x0200` on/off works at all.

VictronConnect on the phone will steal the GATT session. Force-stop it before
using this.

## Install on the laptop

```bash
sudo apt-get install -y python3-venv python3-pip bluez
python3 -m venv ~/.venv/mppt-ble
~/.venv/mppt-ble/bin/pip install -r requirements.txt
sudo usermod -aG bluetooth "$USER"   # then log out/in
```

Scan / on / off (MAC of the captain MPPT):

```bash
~/.venv/mppt-ble/bin/python -m mppt_ble scan
~/.venv/mppt-ble/bin/python -m mppt_ble read --mac DC:AD:B0:54:DB:4E
~/.venv/mppt-ble/bin/python -m mppt_ble off --mac DC:AD:B0:54:DB:4E
~/.venv/mppt-ble/bin/python -m mppt_ble on --mac DC:AD:B0:54:DB:4E
```

HTTP on port 5340 (same JSON shape as the phone `/charger` commands):

```bash
~/.venv/mppt-ble/bin/python -m mppt_ble serve --mac DC:AD:B0:54:DB:4E --bind 0.0.0.0:5340
curl -sS -X POST http://127.0.0.1:5340/charger -d '{"action":"off"}'
```

systemd user unit: copy `mppt-ble.service` to `~/.config/systemd/user/`,
`systemctl --user daemon-reload && systemctl --user enable --now mppt-ble`.

## Deploy on a new laptop (GitHub)

The downstairs machine can die. Clone the repo, install BlueZ, run the same
commands. Do not bake LAN URLs or secrets into git.

```bash
git clone https://github.com/lakshaysethi2/victron-bland-exporter.git
cd victron-bland-exporter/linux
sudo apt-get install -y python3-venv python3-pip bluez
python3 -m venv ~/.venv/mppt-ble
~/.venv/mppt-ble/bin/pip install -r requirements.txt
~/.venv/mppt-ble/bin/python -m mppt_ble scan
```

Point `--metrics` at whichever phone is exporting Instant Readout on the LAN.

## Cloud-reset (power-station MPPT wake-up)

The Victron feeds a power station that has its own MPPT. After clouds, that
station often stays at a low charge rate until the Victron is toggled off then
on (a few seconds only — never leave it off in the sun).

The loop reads `victron_solar_power_watts` from the phone exporter, tracks the
recent peak, and if live watts stay below `drop_fraction * peak` for `--hold`
seconds it pulses OFF `--off-seconds` then ON, then waits `--cooldown`.

```bash
# watch only, no BLE writes
~/.venv/mppt-ble/bin/python -m mppt_ble.yield_reset \
  --mac DC:AD:B0:54:DB:4E \
  --metrics http://192.168.10.12:5338/metrics \
  --dry-run

# live (4s off, 12min cooldown)
~/.venv/mppt-ble/bin/python -m mppt_ble.yield_reset \
  --mac DC:AD:B0:54:DB:4E \
  --metrics http://192.168.10.12:5338/metrics
```

Do not start this until Instant Readout is flowing (`victron_devices_total` > 0)
and VictronConnect is closed.
