# Setup Guide — Victron BLE Exporter

Turn an old Android phone into a wireless bridge between your **Victron MPPT solar charger** and a **Prometheus + Grafana** monitoring stack — with no port-forwarding, no static IP, and no cloud account for the quick-start path.

> **You need:**
> - An Android phone (arm64 / 64-bit, Android 8+, API 26+) with Bluetooth — an old one you can leave plugged in is ideal
> - A Victron MPPT (or SmartShunt) with **Instant Readout** enabled (see [Get your encryption key](#get-your-victron-encryption-key))
> - A machine (or small VPS) running Docker for Prometheus + Grafana
> - ~30 minutes

---

## Table of contents

1. [How it works](#how-it-works)
2. [Get your Victron encryption key](#get-your-victron-encryption-key)
3. [Build the APK](#build-the-apk)
4. [Install the app](#install-the-app)
5. [Set up the tunnel](#set-up-the-tunnel)
6. [Configure Prometheus](#configure-prometheus)
7. [Set up Grafana + import the dashboard](#set-up-grafana--import-the-dashboard)
8. [Troubleshooting](#troubleshooting)
9. [Privacy & security notes](#privacy--security-notes)

---

## How it works

```
┌──────────────────────────┐     BLE (Instant Readout)     ┌──────────────────────────────┐
│  Victron MPPT / SmartShunt│ ─────────────────────────────▶│  Android phone (this app)     │
│  (broadcasts ~1×/sec)     │                               │  • parses BLE advertisements  │
└──────────────────────────┘                               │  • decrypts with your key     │
                                                            │  • serves /metrics on :5338  │
                                                            │  • runs embedded cloudflared  │
                                                            └──────────────┬───────────────┘
                                                                           │ Cloudflare Tunnel (HTTPS)
                                                                           ▼
┌──────────────────────────┐     HTTPS scrape (every 5s)   ┌──────────────────────────────┐
│  Grafana dashboard       │ ◀─────────────────────────────│  Prometheus                   │
│  (import deploy/         │                               │  job_name: victron-mppt       │
│   grafana-dashboard.json)│                               └──────────────────────────────┘
└──────────────────────────┘
```

**The data path in one sentence:** your phone reads the MPPT's Bluetooth "Instant Readout" broadcasts, decrypts them, exposes them as Prometheus metrics on a local port, and publishes that port to the internet through a Cloudflare Tunnel — so Prometheus (anywhere) can scrape it over HTTPS and Grafana can chart it.

**Why a tunnel instead of port-forwarding?**
- No router config, no static public IP, no dynamic-DNS
- The phone is behind a NAT (or on cellular) and nothing is exposed on a real port
- Cloudflare handles TLS for you

---

## Get your Victron encryption key

Victron Instant Readout data is AES-encrypted. You need the 32-character hex key, which lives in the VictronConnect app:

1. Install **VictronConnect** (Android or iOS) and connect to your MPPT/device.
2. Go to **Settings → Product Info**.
3. Scroll to **Instant Readout via Bluetooth** and enable it if it isn't already.
4. Tap **Encryption data** / **Show encryption key**.
5. Copy the 32-character hex string, e.g. `a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6`.

You only need this once — the app saves it per device.

---

## Build the APK

The cloudflared binary is already bundled in the repo (`app/src/main/jniLibs/arm64-v8a/libcloudflared.so`), so a normal build just works. But if you want to build the tunnel binary yourself (or you're just curious about the magic that makes DNS work on Android — [see Option B](#option-b-rebuild-cloudflared-yourself-cgondk)), this section covers both.

### Option A: Build the app with the bundled binary (recommended)

**Requirements**
- Android SDK with platform 35 (compileSdk). Point Gradle at it via `local.properties`:

  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

- JDK 17
- An arm64 Android device to run it on (the bundled binary is arm64-v8a only)

**Build**

```bash
git clone https://github.com/lakshaysethi2/victron-bland-exporter.git
cd victron-bland-exporter
./gradlew assembleDebug
```

Your APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Skip the local build and grab the debug APK from the latest [GitHub Release](https://github.com/lakshaysethi2/victron-bland-exporter/releases/latest) (`victron-ble-exporter.apk`). CI also keeps a copy on the workflow artifact.

Alternatively there's a Docker build path (`make build` / `docker compose run --rm builder`) that uses the `mingc/android-build-box` image — handy if you don't want to install the SDK locally.

### Option B: Rebuild cloudflared yourself (cgo/NDK)

The bundled binary is **not** the stock static Go release. It's a **cgo/NDK rebuild** (`CGO_ENABLED=1` against the Android NDK), and that detail is the whole reason the tunnel's DNS works on Android — see [the `[::1]:53` failure mode](#the-153-child-dns-failure-the-hard-won-one).

If you want to reproduce it (or bump the cloudflared version):

```bash
# From a cloudflared checkout at the tag you want to ship (e.g. 2026.7.3; needs Go >= its go.mod requirement)
CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
  CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
  go build -trimpath -o libcloudflared.so ./cmd/cloudflared
```

where `$NDK` is your NDK r27c (or newer) installation. Then replace `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` with the output and rebuild the app.

**Why cgo/NDK?** A static Go cloudflared resolves DNS itself by reading `/etc/resolv.conf`, which on Android points at loopback `::1`/`127.0.0.1` — and nothing listens there inside the app's sandbox. The cgo build instead resolves via bionic `getaddrinfo` → netd, the exact same path the app itself uses, so the child process's DNS works. Go prefers the cgo resolver on Android (`goosPrefersCgo` in `go/src/net/conf.go`).

The gradle/manifest flags that make the dynamically-linked binary run from the APK:

- `app/build.gradle.kts`:

  ```kotlin
  packaging {
      jniLibs { useLegacyPackaging = true }
  }
  ```

- `app/src/main/AndroidManifest.xml`:

  ```xml
  <application android:extractNativeLibs="true" ...>
  ```

These extract the `.so` to `nativeLibraryDir` (the only place Android 10+ will `exec` from — app-private dirs are mounted `noexec`).

> ⚠️ **Guardrail:** don't accidentally ship a static binary back into `jniLibs`. The repo has a unit test (`app/src/test/kotlin/com/lakshaysethi/victronbleexporter/tunnel/TunnelBinaryInspectorTest.kt`) and a runtime DNS self-test that both fail hard if the bundled binary isn't dynamically linked.

---

## Install the app

1. Copy `app-debug.apk` to the phone (USB, ADB, or any file-transfer method).
2. Tap the APK on the phone and allow "Install unknown apps" for your file manager/browser when prompted.
3. Open **Victron BLE Exporter**.
4. Grant the requested permissions: **Bluetooth**, **Location** (needed for BLE scanning on Android), **Notifications**, and **Ignore battery optimizations** (so the tunnel survives while the phone sleeps).
5. The app auto-scans for nearby Victron devices — tap yours to auto-fill the MAC, then paste the 32-char encryption key.
6. Enable **auto-start on boot** if you want it running unattended.

> **MAC privacy note:** the app stores the device MAC locally and includes it in the `mac=` metric label. If you ever expose your own Prometheus publicly, remember that label is there. (This guide deliberately shows placeholder device IDs only.)

---

## Set up the tunnel

The app has two tunnel modes.

### Quick Tunnel (zero-config — good to start with)

Tap **Start Tunnel** (quick tunnel mode). The app runs cloudflared with:

```
cloudflared --no-autoupdate tunnel --url http://localhost:5338
```

and within a few seconds the app shows a public URL like:

```
https://your-subdomain.trycloudflare.com
```

Prometheus can scrape `https://your-subdomain.trycloudflare.com/metrics` from anywhere.

> ⚠️ **Quick-tunnel URLs are ephemeral.** The `*.trycloudflare.com` URL changes every time the tunnel restarts, and Cloudflare may rate-limit or drop idle quick tunnels. Great for testing; not for production.

### Named Tunnel (recommended for stability)

A named tunnel gives you a **stable** public hostname on your own domain (`https://victron.yourdomain.com`), surviving app restarts and reconnects.

1. Create a free Cloudflare account and add your domain (nameservers pointed at Cloudflare).
2. Create a named tunnel + a token:

   ```bash
   cloudflared tunnel login
   cloudflared tunnel create victron-mppt
   cloudflared tunnel route dns victron-mppt victron.yourdomain.com
   ```

3. Paste the **tunnel token** (`cloudflared tunnel token <name>` prints it) into the app's named-tunnel field and tap **Start Tunnel**. The app runs:

   ```
   cloudflared --no-autoupdate tunnel run --token <your-token>
   ```

Your metrics are now permanently at `https://victron.yourdomain.com/metrics`.

> 🔒 Your token never leaves the device, is redacted in the app's debug logs, and is stored only in app-local storage.

### Verify it works

From any machine:

```bash
curl -s https://your-subdomain.trycloudflare.com/metrics
```

You should see metrics like:

```
# HELP victron_solar_power_watts Solar power
# TYPE victron_solar_power_watts gauge
victron_solar_power_watts{device="Victron-0xXXXX",mac="AA:BB:CC:DD:EE:FF",type="mppt"} 123.4
```

(Values and the MAC shown here are placeholders — your output will differ.)

---

## Configure Prometheus

Add this scrape job to `prometheus.yml`. It's templated for the **named-tunnel** case; swap the hostname for your quick-tunnel URL while testing.

```yaml
scrape_configs:
  - job_name: 'victron-mppt'
    scheme: https
    metrics_path: '/metrics'
    scrape_interval: 5s
    static_configs:
      - targets: ['victron.yourdomain.com']
    # If your tunnel host is behind a proxy with a non-443 port, use e.g. 'host:8443'
```

The live settings this template is based on:

| Setting | Value |
|---|---|
| `job_name` | `victron-mppt` |
| `scheme` | `https` |
| `metrics_path` | `/metrics` |
| scrape interval | `5s` (the MPPT broadcasts ~1×/s; 5s keeps the charts smooth without hammering the tunnel) |

Reload Prometheus (`curl -X POST localhost:9090/-/reload` or restart the container) and check **Status → Targets** — the `victron-mppt` target should be `UP`.

### Available metrics

| Metric | Unit | Meaning |
|---|---|---|
| `victron_battery_voltage_volts` | V | Battery voltage |
| `victron_battery_current_amps` | A | Battery current (sign = charge/discharge) |
| `victron_solar_power_watts` | W | Current solar yield |
| `victron_yield_today_wh` | Wh | Yield since midnight |
| `victron_load_current_amps` | A | Load current (if the device reports it) |
| `victron_soc_percent` | % | State of charge (SmartShunt/battery monitor) |
| `victron_charge_state` | enum | 0=OFF, 3=BULK, 4=ABSORPTION, 5=FLOAT |
| `victron_rssi_dbm` | dBm | BLE signal strength to the phone |
| `victron_last_seen_timestamp` | unix s | Time of the last decrypted Instant Readout (not scrape time) |
| `victron_up` | 0/1 | `1` while that readout is younger than 90s |
| `victron_devices_total` | count | Devices with a fresh Instant Readout (drops to 0 if BLE is lost) |

Live Instant Readout gauges (voltage, current, watts, yield, SoC, RSSI, charge state) are omitted after 90 seconds without a new advertisement, so Grafana does not keep plotting the last-known watts as if the charger were still talking.

Every device-scoped metric carries `device` (model name), `mac` (device MAC — see the privacy note), and `type` labels.

---

## Set up Grafana + import the dashboard

### 1. Add the Prometheus datasource

In Grafana: **Connections → Data sources → Add data source → Prometheus**, then set:

- **URL**: `http://prometheus:9090` (if Grafana and Prometheus share a compose network) or `http://<prometheus-host>:9090`

Click **Save & test** — it should report success.

### 2. Import the dashboard (one click)

1. Grab the dashboard JSON from this repo: `deploy/grafana-dashboard.json` (raw: `https://raw.githubusercontent.com/lakshaysethi2/victron-bland-exporter/main/deploy/grafana-dashboard.json`)
2. In Grafana: **Dashboards → New → Import** (or go to `/dashboard/import`).
3. **Upload dashboard JSON file** (or paste the JSON) → **Load**.
4. When prompted, select your **Prometheus** datasource for the `DS_PROMETHEUS` variable → **Import**.

That's it. The **Solar — Victron MPPT** dashboard appears with five panels:

- **Yield today** (stat, Wh)
- **Solar power** (time series, W)
- **Battery voltage** (time series, V)
- **Battery current** (time series, A)
- **Devices online** (stat, count)

It auto-refreshes every 10 seconds and defaults to the last 6 hours. If you have multiple Victron devices, the `{{device}}` legend keeps their series separate automatically.

---

## Troubleshooting

### The `[::1]:53` child-DNS failure (the hard-won one)

**Symptom:** the tunnel starts and instantly dies; the debug log shows cloudflared exiting with something like:

```
dial tcp: lookup api.trycloudflare.com on [::1]:53: read: connection refused
```

**Why it happens:** the statically-linked Go cloudflared binary resolves DNS with Go's own resolver, which reads `/etc/resolv.conf`. On Android that file points at the loopback addresses `::1` and `127.0.0.1` — where nothing is listening inside the app's network sandbox. `bindProcessToNetwork()` on the parent process does **not** fix the child's DNS, because the child's DNS target is a loopback address with no listener, regardless of which network the process is bound to.

**The fix (already shipped in this repo):** the bundled binary is a **cgo/NDK rebuild** — dynamically linked against bionic libc — so Go's `net` package takes the cgo resolver path (`getaddrinfo` → netd), exactly like every normal Android app. Netd knows the real network and DNS servers, and the child resolves fine. `readelf` on the shipped binary shows `NEEDED libc.so` and an interpreter of `/system/bin/linker64`; a static build has no dynamic section at all.

**How to confirm your binary is the right kind (on-device):**

1. In the app, tap **DNS Self-Test**. It must report **PASSED** *and* `libcloudflared.so dynamically linked (child DNS via bionic libc → netd)`.
2. Tap **Share Debug Logs** and check the `Cloudflared resolver path:` line — it must say `dynamic (DNS via bionic libc → netd)`. A `STATIC` line means a wrong binary got shipped and child DNS will fail no matter what the app does.
3. Start the Quick Tunnel: the log must show `Registered tunnel connection` and a `https://*.trycloudflare.com` URL.

If you rebuilt cloudflared yourself, use the [cgo/NDK build command](#option-b-rebuild-cloudflared-yourself-cgondk) — shipping a static build is exactly what causes this bug.

### The tunnel exits instantly with no obvious error

The debug log tells you everything. The **Share Debug Logs** button bundles the last 200 cloudflared output lines, the exit code, run duration, network-bind/DNS preflight results, the self-test report, the redacted command line, and device info — share that file when asking for help.

Common causes:
- **Battery optimization killing the process** — make sure "Ignore battery optimizations" is granted, and check the app survives screen-off.
- **`--no-autoupdate` is missing** — never run a manually-copied cloudflared without it; the auto-updater can't rewrite its own read-only binary in `nativeLibraryDir` and exits. The app always passes it.
- **Wrong ABI** — the bundled binary is arm64-v8a only. On a 32-bit or x86 phone the app reports `cloudflared bundled for arm64 only` and won't start a tunnel.

### The tunnel connects but Prometheus shows the target as DOWN

1. `curl` the tunnel URL from the Prometheus host — can it reach `https://<your-tunnel>/metrics`?
2. Check the **scrape config**: `scheme: https`, `metrics_path: /metrics`, correct host. Quick-tunnel URLs change on every restart — update the target after a tunnel restart (or use a named tunnel).
3. Check Prometheus **Status → Targets** for the exact error string.

### I can't see my device in the app's scan list

- Enable **Location** permission (Android requires it for BLE scanning).
- Keep the phone within ~10 m of the MPPT.
- Make sure **Instant Readout** is enabled on the device (VictronConnect → Settings → Product Info → Instant Readout).

---

## Privacy & security notes

- The metrics carry the device **MAC** in the `mac=` label. This repo's screenshots/docs deliberately use placeholders (`Victron-0xXXXX`, `AA:BB:CC:DD:EE:FF`). If you expose Prometheus publicly, consider a label-stripping relabel or a firewall rule.
- Quick-tunnel URLs are public once running — anyone with the URL can read your metrics. A **named tunnel with Cloudflare Access** in front is a stronger setup for a public instance.
- The app stores the encryption key in app-local storage and never logs it. Tunnel tokens are redacted in debug logs.

---

## Credits

- BLE parser ported from [keshavdv/victron-ble](https://github.com/keshavdv/victron-ble)
- ESP32 implementations by chrisj7903, wytr, SH3D and others
- MIT licensed — contributions welcome
