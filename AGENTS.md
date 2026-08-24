# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Cloudflared on Android

- Binary is bundled as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` and executed from `nativeLibraryDir` (Android 10+ noexec on app-private dirs).
- The bundled binary MUST be the **cgo/NDK build** (dynamically linked, NEEDED libc.so, interp `/system/bin/linker64`). Static Go cloudflared resolves DNS itself from `/etc/resolv.conf` → loopback `::1`/`127.0.0.1` → `connection refused` in the app sandbox; `bindProcessToNetwork` cannot fix that (child's DNS target is a loopback address with no listener). The cgo build resolves via bionic `getaddrinfo` → netd like every native app (Go prefers cgo resolver on Android). `TunnelBinaryInspectorTest` fails the build if a static binary lands back in jniLibs.
- Rebuild command (host needs Go ≥ cloudflared's `go.mod` requirement + NDK; see git history of `fm/mppt-tunnel-child-dns` for the full recipe incl. qemu-x86_64 binfmt on arm64 hosts):
  `CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang go build -trimpath -o libcloudflared.so ./cmd/cloudflared` (from a cloudflared checkout at the shipped tag; cloudflared 2026.7.3 needs Go 1.26).
- `TunnelNetworkPrep.prepare` binds the parent process to the active network and preflights DNS via Android APIs before exec; keep the bind (correct + harmless for the parent) but know it does not cover the child's DNS.
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. SDK via `local.properties` `sdk.dir` (gitignored).
- Public CI: `.github/workflows/ci.yml` runs `testDebugUnitTest` + `assembleDebug` on `ubuntu-latest`, uploads the debug APK artifact, and on `main` / `workflow_dispatch` publishes it as the rolling `latest` GitHub Release (`victron-ble-exporter.apk` + `latest.json`). Do not add a self-hosted workflow on this public repo.
- Instant Readout `/metrics` are omitted after 90s without a new advertisement (`MetricsStore.FRESH_MS`); `victron_last_seen_timestamp` is the packet time, not scrape time, and `victron_up` / `victron_devices_total` follow that window so a lost BLE link does not keep last-known watts on the dashboard.
- SmartShunt fixture `aux_mode` is 3 (`AuxMode.DISABLED` in keshavdv/victron-ble), not 0. Tunnel unit tests are under `tunnel/*Test`.
- Stale Gradle daemons: if `./gradlew` fails with `NoSuchFileException` referencing a `/tmp/fm-mppt-tunnel-*/gradle-8.7/lib/...` path, a daemon from a previous lane's run is still alive with a deleted temp distribution. Kill it (`pkill -f 'gradle-launcher-8.7'` or the PID from `ps aux | grep GradleDaemon`) and re-run; the real distribution lives under `~/.gradle/wrapper/dists/gradle-8.7-bin/`.

## Charger control over BLE

- The Instant Readout advertisements the app parses are read-only. Charger on/off is a write to the proprietary VictronConnect GATT service `306b0001-b081-4037-83dc-e59fcc3cdfd0` (chars `306b0002` control / `306b0003` commands / `306b0004` bulk), register `0x0200` device mode: `1`=on, `0`/`4`=off. Protocol + verified frame layouts are documented in `charger/ChargerProtocol.kt` (researched from Victron's BlueSolar/SmartSolar HEX protocol Rev 18, the VictronConnect APK register metadata, and the pysmartsolar / Olen solar-monitor / Mrkvak victron-linux open-source implementations).
- The device requires BLE pairing; PIN is on the sticker or `000000`. Writes are acknowledged (`WRITE_TYPE_DEFAULT`); fire-and-forget `WRITE_TYPE_NO_RESPONSE` wedges the stack so the next frame is refused. The device echoes the new mode in `08 03 19 02 00 41 <mode>` notifications. Pre-API-33 must set `char.value` before `writeCharacteristic`. Connect must count down the connected latch — otherwise every session times out after 12s even when GATT is up.
- Implementation: `charger/ChargerController.kt` (one GATT session per op, latches + dedicated HandlerThread, serialized by a Mutex; also voltage reads/writes for 0xEDEF/0xEDF7/0xEDF6/0xED4/0xDD5 with readback), `charger/ChargerSchedule.kt` + `data/ChargerScheduleStore.kt` (daily window, manual override until next boundary), `ChargerDebugLog` ring buffer (200 lines) included in Share Debug Logs. Schedule is enforced while the foreground service runs; the service stays up after the UI is dismissed (`stopWithTask=false`, lifetime wake lock).
- Voltage settings: battery system voltage (`0xEDEF`, e.g. 12/24/48) + absorption/float/equalisation + live charger voltage + **panel/PV voltage** (`0xEDBB`, 0.01 V; Instant Readout does not carry it; `0xFFFF` = night NA) over the same GATT service. Wired to UI in `MainActivity` (Voltage Settings card + confirm dialogs), to `AppState.voltageSettings` + `PrometheusExporter` metrics (`victron_*_voltage_volts`, including `victron_panel_voltage_volts` only while fresh), and to remote `GET/POST /voltage` in `RemoteChargerHttp` (same auth secret as `/charger`; web shell at `/voltage`). Service intents: `VOLTAGE_READ`, `VOLTAGE_SET_BATTERY`, `VOLTAGE_SET_CHARGING` — see `ChargerProtocol` for frame layouts and `mppt_registers.json` provenance. Background poll (~60s, 5 min backoff after a failed GATT read) skips when a user charger op is in flight.

## Remote diagnostics + in-app updates

- App-side owner is `diag/` (`AppLog`, `Diagnostics`, `UpdateChecker`). Logs POST to `https://mppt-logs.lak.nz/api/logs`; update check GETs both `https://mppt-logs.lak.nz/api/latest.json` and the public GitHub `releases/latest/download/latest.json` and keeps the higher `versionCode` (a stale-but-up log host must not hide a newer GitHub APK). Server lives in the sibling `mppt-log-server` repo, not this one.
- `ChargerDebugLog` mirrors into `AppLog` so a Send Diagnostics tap includes the BLE exchange. Auto-send is once/hour; the button always sends. Failures stay local.
- Bump `versionCode` / `versionName` in `app/build.gradle.kts` when cutting an APK the downstairs phone should be offered.

## Remote charger control over HTTP/tunnel

- The app serves a remote charger-control surface on the same NanoHTTPD server (port 5338): `GET /charger` (mobile control page), `GET /charger/status` (JSON snapshot of charger mode + daily schedule + fresh Instant Readout watts/volts + last 20 `ChargerDebugLog` lines), `POST /charger` with `{"action":"on"|"off"}`. Authoritative code: `exporter/RemoteChargerHttp.kt` + `data/RemoteChargerStore.kt` (auth secret in plain SharedPreferences, master `enabled` switch — disabled = every /charger* route 404s). Cloud backup / device-transfer rules in `app/src/main/res/xml/` must keep excluding `victron_devices(_fallback)`, `victron_remote_settings`, `victron_charger_settings`, `victron_app_log`, and `victron_diagnostics` — otherwise Google backup ships the tunnel token, Instant Readout keys, and remote secret.
- Auth: the secret must be sent as `X-Remote-Secret` (or `Authorization: Bearer`) header; compared constant-time via `MessageDigest.isEqual`; never logged, never in URLs. The control page is a static shell (login form) because browsers can't attach custom headers to navigations — all functional routes still require the secret.
- Commands are forwarded via the same `CHARGER_SET` service intent as the local UI, so remote flips get the identical BLE readback verification and manual-override/schedule semantics (`performChargerSet` in the service). The HTTP layer is a pure seam (settings/status/mac providers + `ChargerCommandSender`) so `exporter/RemoteChargerHttpTest` runs on the JVM; `RemoteChargerHttpServerTest` exercises real NanoHTTPD over loopback under Robolectric (`@ConscryptMode(OFF)` — same host limitation as TunnelUrlCopyShareTest).

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
