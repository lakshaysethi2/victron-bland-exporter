# Disaster recovery — downstairs MPPT laptop

If this Linux box is destroyed, a replacement host can restore **https://mppt.lak.nz**. This file is the ordered path.

## git-backed vs laptop-only

**git-backed** (clone from GitHub `lakshaysethi2/victron-bland-exporter`):

- Linux exporter: `linux/mppt_ble/`, `linux/yield_config.json`, tests
- Example env: `linux/secrets.env.example`
- Example units: `linux/mppt-ble.service`, `linux/cloudflared-mppt.service`
- This runbook

**laptop-only** (not in git; recreate from backups / Cloudflare / the MPPT sticker):

- `~/.config/mppt/secrets.env` (mode 600)
- `~/.config/mppt/tunnel.token` (Cloudflare named-tunnel token file)
- `~/.config/mppt/ble-pin` (pairing PIN from the SmartSolar sticker)
- `~/.config/mppt/devices.json` (Instant Readout encryption keys, if used)
- `~/.config/mppt/watchdog.sqlite` — **non-critical**. Do not block restore on this file. A new box starts with an empty 2 h fill; auto-pulse is conservative until samples exist.
- systemd enablement: user linger, `mppt-ble` + `node-exporter` user units, `cloudflared-mppt` + `display-idle` system units
- `/etc/systemd/logind.conf.d/10-no-sleep.conf` (sleep/suspend left disabled)
- BlueZ bond for SmartSolar `DC:AD:B0:54:DB:4E`

Do **not** commit `secrets.env`, `tunnel.token`, `ble-pin`, or `watchdog.sqlite`.

## Restore from a known-good tree

`origin/main` has been force-rewound during site work. Do **not** blindly `git checkout origin/main` if that tree is missing Linux GATT/`0xEDBB` or the watchdog.

On a replacement host:

```bash
git clone https://github.com/lakshaysethi2/victron-bland-exporter.git
cd victron-bland-exporter
# Prefer a known-good commit or PR that actually served mppt.lak.nz
# (GATT CONTROL-read after f980, sqlite watchdog). Confirm linux/mppt_ble/client.py
# contains a CONTROL read after f980 before using the tree as production.
```

If GitHub history is unusable, recover the working tree from any surviving clone or tarball of this repo.

## Ordered restore (replacement Linux host)

### 1. Packages and venv

```bash
sudo apt-get install -y python3-venv python3-pip bluez bluetooth
python3 -m venv ~/.venv/mppt-ble
~/.venv/mppt-ble/bin/pip install -r linux/requirements.txt
sudo usermod -aG bluetooth "$USER"   # then log out/in
```

Install `node_exporter` to `~/.local/bin/node_exporter` (listen `127.0.0.1:9100`).
Install `cloudflared` to `/usr/local/bin/cloudflared`.

### 2. Secrets file (key names only)

```bash
mkdir -p ~/.config/mppt
cp linux/secrets.env.example ~/.config/mppt/secrets.env
chmod 600 ~/.config/mppt/secrets.env
```

Fill **values from backup**, not from git. Live key names that must exist or be understood:

| Key | Role |
|---|---|
| `MPPT_REMOTE_SECRET` | `X-Remote-Secret` for `/charger` |
| `MPPT_PUBLIC_HOST` | public host, **mppt.lak.nz** |
| `MPPT_MAC` | SmartSolar `DC:AD:B0:54:DB:4E` |
| `NODE_EXPORTER_URL` | `http://127.0.0.1:9100/metrics` |
| `CLOUDFLARED_TOKEN_FILE` | path to the tunnel token file |
| `MPPT_MAX_PULSES_PER_HOUR` | auto-pulse cap (optional override) |
| `CLOUDFLARE_TUNNEL_TOKEN` | alternate live key; prefer the token **file** |
| `TUNNEL_TOKEN` | alternate live key; prefer the token **file** |
| `MPPT_WATCHDOG_DB` | optional; default `~/.config/mppt/watchdog.sqlite` |

Put the Cloudflare named-tunnel token in `~/.config/mppt/tunnel.token` (mode 600). Point `CLOUDFLARED_TOKEN_FILE` at that path.

### 3. User linger (user units survive without a GUI login)

```bash
sudo loginctl enable-linger "$USER"
loginctl show-user "$USER" -p Linger   # Linger=yes
```

### 4. Bluetooth pair

PIN is laptop-only (`~/.config/mppt/ble-pin` or the sticker). Bond once:

```bash
bluetoothctl power on
bluetoothctl pair DC:AD:B0:54:DB:4E
bluetoothctl trust DC:AD:B0:54:DB:4E
bluetoothctl info DC:AD:B0:54:DB:4E   # Paired: yes, Trusted: yes
```

Close VictronConnect; it steals the only GATT session.

### 5. `mppt-ble` + node-exporter

Live working directory is the clone: `~/code/victron-bland-exporter/linux` (not `~/mppt-ble`). Copy the user unit and point `WorkingDirectory` at the clone `linux/` directory. Bind `0.0.0.0:5338` if the tunnel hits this host (repo example uses `127.0.0.1:5338`).

```bash
mkdir -p ~/.config/systemd/user
cp linux/mppt-ble.service ~/.config/systemd/user/
# edit WorkingDirectory, ExecStart --mac DC:AD:B0:54:DB:4E --bind 0.0.0.0:5338
# EnvironmentFile=%h/.config/mppt/secrets.env
install -m 755 node_exporter ~/.local/bin/node_exporter   # if not already
cat > ~/.config/systemd/user/node-exporter.service <<'EOF'
[Unit]
Description=Prometheus node_exporter (localhost only; scraped via mppt_ble /node/metrics)
After=network.target
[Service]
Type=simple
ExecStart=%h/.local/bin/node_exporter --web.listen-address=127.0.0.1:9100
Restart=on-failure
RestartSec=5
[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now mppt-ble node-exporter
```

Check:

```bash
systemctl --user is-active mppt-ble node-exporter
curl -sS http://127.0.0.1:5338/metrics | head
curl -sS http://127.0.0.1:5338/node/metrics | head
```

### 6. Cloudflare named tunnel (`CLOUDFLARED_TOKEN_FILE`)

Ingress for **mppt.lak.nz** → `http://127.0.0.1:5338` (or `0.0.0.0:5338` origin). Token lives in the token file, not in git.

```bash
sudo cp linux/cloudflared-mppt.service /etc/systemd/system/cloudflared-mppt.service
# ExecStart must use --token-file pointing at ~/.config/mppt/tunnel.token
# (or ${CLOUDFLARED_TOKEN_FILE} via EnvironmentFile)
sudo systemctl daemon-reload
sudo systemctl enable --now cloudflared-mppt
curl -sSI https://mppt.lak.nz/metrics | head
```

### 7. Sleep/suspend left disabled

```bash
sudo mkdir -p /etc/systemd/logind.conf.d
sudo tee /etc/systemd/logind.conf.d/10-no-sleep.conf >/dev/null <<'EOF'
[Login]
HandleSuspend=ignore
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
IdleAction=ignore
EOF
sudo systemctl restart systemd-logind
```

Optional screen blank (does not sleep the machine): `display-idle.service` from `~/mppt-ble/install-display-idle.sh` on the old appliance, or the copy under `/usr/local/bin/display-idle`.

Boot as an appliance: `sudo systemctl set-default multi-user.target`.

### 8. Watchdog sqlite (non-critical)

Do not restore `watchdog.sqlite` unless it is convenient. Empty DB on a new box is expected. Auto-pulse waits until the 2 min / 2 h windows fill. Not a restore-blocking backup.

## After restore

- https://mppt.lak.nz/charger (secret header from `MPPT_REMOTE_SECRET`)
- https://mppt.lak.nz/metrics
- linger on, sleep ignored, SmartSolar paired, tunnel up
