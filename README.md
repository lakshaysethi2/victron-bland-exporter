# Victron BLE Exporter

**Turn an old Android phone into a wireless bridge from your Victron MPPT to Prometheus + Grafana — no port-forwarding required.**

[![Platform](https://img.shields.io/badge/platform-Android%208%2B-3DDC84?logo=android&logoColor=white)](guide.md#install-the-app)
[![License](https://img.shields.io/github/license/lakshaysethi2/victron-bland-exporter)](LICENSE)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](app/src/main/kotlin/com/lakshaysethi/victronbleexporter)
[![BLE](https://img.shields.io/badge/protocol-Victron%20Instant%20Readout-0e83cd)](guide.md#how-it-works)

The app runs on an old Android phone next to your Victron MPPT or SmartShunt, reads the BLE **Instant Readout** broadcasts, decrypts them with the key from VictronConnect, exposes a Prometheus `/metrics` endpoint — and publishes it to the internet through a **built-in Cloudflare Tunnel**, so your Prometheus server can scrape it from anywhere over HTTPS.

```
Victron MPPT (BLE) → Android phone (this app) → cloudflared tunnel → Prometheus → Grafana
```

> ✨ **The hard part is done for you:** the bundled `cloudflared` is a custom **cgo/NDK rebuild** so its child-process DNS actually works on Android — a notorious failure mode that kills every stock static build (see [the `[::1]:53` story](guide.md#the-153-child-dns-failure-the-hard-won-one)).

---

## Screenshots

**Grafana dashboard** — imported in one click from [`deploy/grafana-dashboard.json`](deploy/grafana-dashboard.json):

![Solar — Victron MPPT Grafana dashboard](docs/screenshots/grafana-dashboard.png)

**App** — main screen and debug-log sharing (screenshots arriving soon):

| Main screen | Debug log |
|---|---|
| ![App main screen](docs/screenshots/app-main.png) | ![App debug log](docs/screenshots/app-debug.png) |

---

## Quick start

The full, beginner-friendly walkthrough is in **[`guide.md`](guide.md)** — build the APK, sideload, set up the tunnel, configure Prometheus, and import the Grafana dashboard, end-to-end.

1. **Build & install** — `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`, sideload on any arm64 Android 8+ phone ([instructions](guide.md#build-the-apk))
2. **Add your key** — grab the 32-char Instant Readout key from VictronConnect ([how](guide.md#get-your-victron-encryption-key))
3. **Start the tunnel** — quick tunnel for testing (`https://your-subdomain.trycloudflare.com`), named tunnel for a stable hostname ([setup](guide.md#set-up-the-tunnel))
4. **Scrape it** — 5-second HTTPS scrape job in Prometheus ([config](guide.md#configure-prometheus))
5. **Dashboard** — one-click Grafana import of [`deploy/grafana-dashboard.json`](deploy/grafana-dashboard.json) ([instructions](guide.md#set-up-grafana--import-the-dashboard))

---

## Features

- 🔄 **BLE → Prometheus in real time** — full [keshavdv/victron-ble](https://github.com/keshavdv/victron-ble) Instant Readout parser (AES-128-CTR) for MPPT solar chargers and SmartShunt battery monitors
- 🌐 **Cloudflare Tunnel with working child DNS** — embedded `cloudflared`, rebuilt with cgo/NDK so DNS resolves through Android's netd instead of dying on the loopback `[::1]:53` trap
- 📈 **Prometheus `/metrics` endpoint** (OpenMetrics, port 5338) — voltage, current, solar power, yield today, state of charge, charge state, RSSI, device count
- ⚡ **Charger control over BLE** — enable/disable the MPPT charger (register `0x0200` device mode via the VictronConnect GATT service) with visible state, readback verification, and a configurable daily on/off schedule (default 08:30 → 18:00)
- 🌐 **Remote charger control** — flip the charger from any browser at `https://mppt.lak.nz/charger` (named tunnel) or `http://<phone-ip>:5338/charger` (LAN), protected by a shared secret you set in the app
- 🖥️ **Importable Grafana dashboard** — [`deploy/grafana-dashboard.json`](deploy/grafana-dashboard.json): solar power, battery voltage/current, yield, devices online
- 🐞 **Share Debug Logs** — one tap bundles the last 200 cloudflared lines, exit code, network-bind/DNS preflight, and a DNS self-test report, with clipboard fallback — *the* tool for diagnosing tunnel issues
- 🔍 **DNS Self-Test button** — verifies on-device that the bundled binary is the dynamic cgo build (fails hard if a static binary sneaks back in)
- 📱 **Easy discovery UX** — auto-scans nearby Victron devices, tap to auto-fill the MAC, paste the key
- 🔋 **Runs unattended** — foreground service, auto-start on boot, battery-optimization handling, multi-device support
- 🌳 **Open source (MIT)** — no cloud dependency for the app itself; quick tunnels need no account at all

---

## Charger Control (enable / disable + schedule)

The MPPT advertises live data through the read-only Instant Readout protocol, but **charger on/off is a write** to the proprietary VictronConnect GATT service:

- Service `306b0001-b081-4037-83dc-e59fcc3cdfd0` (legacy SmartSolar protocol), characteristics `306b0002` (control), `306b0003` (commands), `306b0004` (bulk)
- Register `0x0200` **device mode**: `1` = Charger on, `0` or `4` = Charger off (Victron "VE.Direct Protocol / BlueSolar and SmartSolar MPPT" Rev 18 + VictronConnect APK register metadata)
- Read frame `05 03 81 19 02 00`, write frame `06 03 82 19 02 00 41 <mode>`, response `08 03 19 02 00 41 <mode>`

In the app's **Charger Control** section:
1. Enter the MPPT's MAC (auto-filled from your saved devices).
2. Tap **Enable Charger** / **Disable Charger** — the app connects, runs the session handshake, writes the mode and reads the value back so you see the resulting device state (also in **Share/Copy Debug Logs** under "Charger control").
3. **Read Current State** refreshes the displayed state without writing.
4. Optionally enable the **daily schedule** (on time / off time, defaults 08:30 / 18:00). The service re-checks and applies it every 30 seconds while running; a manual Enable/Disable pauses the schedule until the next window boundary (shown in the UI).

The current state is exposed as the `victron_charger_enabled` metric (`1` = charger on, `0` = off, `-1` = unknown).

### Remote control (browser / tunnel)

In the app's **Remote Charger Control** section, enable remote control and set a secret (min 8 chars). The app then serves:

- `GET  /charger` — mobile control page (login shell; everything on it requires the secret)
- `GET  /charger/status` — JSON state (`mode`, `busy`, `lastAction`, …)
- `POST /charger` — `{"action":"on"|"off"}` flips the charger

Auth: every status/command call must send the secret as an `X-Remote-Secret` (or `Authorization: Bearer`) header. It is compared constant-time and **never logged or placed in a URL**; the page keeps it only in the browser session. When remote control is disabled, all `/charger*` routes answer 404. A remote flip goes through the same `CHARGER_SET` path as a local tap, so it gets the same readback verification and manual-override/schedule semantics.

Pairing: the first connection prompts for a Bluetooth PIN. Use the PIN printed on the product sticker, or `000000` (the common Victron default).

> **Limitation**: the schedule is enforced only while the app is running (foreground service active). 24/7 scheduling would need a follow-up foreground-service/power-management change — it is called out in the UI too.

---

## Architecture

```
┌──────────────────────────┐     BLE 0x02E1 (Instant Readout)
│  Victron MPPT / SmartShunt│ ────────────────────────────────┐
└──────────────────────────┘                                  ▼
                                    ┌─────────────────────────────────────────┐
                                    │  Android foreground service (this app)  │
                                    │                                         │
                                    │  BLE Scanner → VictronParser (AES-CTR)  │
                                    │        → MetricsStore → /metrics :5338  │
                                    │                                         │
                                    │  cloudflared (cgo/NDK, bundled .so)     │
                                    │    └─ quick tunnel  /  named tunnel     │
                                    └───────────────────┬─────────────────────┘
                                                        │ HTTPS (Cloudflare Tunnel)
                                                        ▼
                                    ┌─────────────────────────────────────────┐
                                    │  Prometheus (scrape: 5s, /metrics)      │
                                    │        → Grafana (imported dashboard)   │
                                    └─────────────────────────────────────────┘
```

Key files:

```
app/src/main/kotlin/com/lakshaysethi/victronbleexporter/
├── parser/            VictronParser.kt, BitReader.kt, DeviceEnums.kt   (BLE decryption)
├── exporter/          PrometheusExporter.kt, MetricsStore.kt           (/metrics server)
├── tunnel/            CloudflaredManager.kt, TunnelNetworkPrep.kt,
│                      TunnelBinaryInspector.kt                         (tunnel + DNS)
├── data/              DeviceRepository.kt                              (key storage)
└── service/           VictronBleExporterService.kt                     (foreground service)
app/src/main/jniLibs/arm64-v8a/libcloudflared.so                        (bundled binary)
```

---

## Project status

Core functionality is complete and battle-tested on real hardware: BLE parsing, Prometheus export, tunnel with working child DNS, debug-log sharing, charger control, and the Grafana dashboard. Community help welcome on:

- More device types (Inverter, DC/DC converters, etc.)
- Nicer onboarding UI and encrypted key storage
- F-Droid packaging / GitHub Releases with signed APKs
- Test reports from other Victron hardware

## Contributing

PRs welcome — see [`guide.md`](guide.md) for the build and the cgo/NDK cloudflared recipe if you touch the tunnel binary. Please run the JVM unit tests (`./gradlew test`) — the `TunnelBinaryInspectorTest` guards the bundled binary's linkage.

## License

[MIT](LICENSE) — parser logic ported from [keshavdv/victron-ble](https://github.com/keshavdv/victron-ble) (also MIT).
