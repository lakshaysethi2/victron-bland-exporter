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
- Pre-existing: `VictronParserTest` "real SmartShunt advert" can fail on JVM unit tests; tunnel unit tests are under `tunnel/*Test`.
- Stale Gradle daemons: if `./gradlew` fails with `NoSuchFileException` referencing a `/tmp/fm-mppt-tunnel-*/gradle-8.7/lib/...` path, a daemon from a previous lane's run is still alive with a deleted temp distribution. Kill it (`pkill -f 'gradle-launcher-8.7'` or the PID from `ps aux | grep GradleDaemon`) and re-run; the real distribution lives under `~/.gradle/wrapper/dists/gradle-8.7-bin/`.

## Charger control over BLE

- The Instant Readout advertisements the app parses are read-only. Charger on/off is a write to the proprietary VictronConnect GATT service `306b0001-b081-4037-83dc-e59fcc3cdfd0` (chars `306b0002` control / `306b0003` commands / `306b0004` bulk), register `0x0200` device mode: `1`=on, `0`/`4`=off. Protocol + verified frame layouts are documented in `charger/ChargerProtocol.kt` (researched from Victron's BlueSolar/SmartSolar HEX protocol Rev 18, the VictronConnect APK register metadata, and the pysmartsolar / Olen solar-monitor / Mrkvak victron-linux open-source implementations).
- The device requires BLE pairing; PIN is on the sticker or `000000`. Writes are write-without-response; the device echoes the new mode in `08 03 19 02 00 41 <mode>` notifications (status 133 in `onCharacteristicWrite` is a known no-response-write quirk, not an error).
- Implementation: `charger/ChargerController.kt` (one GATT session per op, latches + dedicated HandlerThread, serialized by a Mutex), `charger/ChargerSchedule.kt` + `data/ChargerScheduleStore.kt` (daily window, manual override until next boundary), `ChargerDebugLog` ring buffer (200 lines) included in Share Debug Logs. Schedule is enforced only while the foreground service runs — by design, called out in UI + README.

## Remote diagnostics + in-app updates

- Log server lives at `https://mppt-logs.lak.nz` — NOT `mppt.lak.nz` (that hostname belongs to the device's own cloudflared tunnel).
- `diag/` package: `AppLog` (bounded 500-entry log persisted in SharedPreferences, throttled ~2s; `reload()` re-reads for restart tests), `Diagnostics` (payload builder + HttpURLConnection POST to `https://mppt-logs.lak.nz/api/logs`, stable persisted device_id, auto-send rate-limited to 1/hour), `UpdateChecker` (`GET /api/latest.json`, versionCode comparison, relative apkUrl resolved to absolute). No new HTTP deps — plain `HttpURLConnection` on `Dispatchers.IO`.
- Live server schema is stricter than the original brief sketch: `ts` must be a STRING (app sends ISO-8601 UTC via `Diagnostics.isoTime`), `level` must be `info|warn|error` lowercase (mapped via `Diagnostics.serverLevel`; CHARGER→info). Non-conforming payloads get 422; extra device fields are ignored.
- `ChargerDebugLog.append` mirrors every line into `AppLog` (level CHARGER) so the diagnostics payload captures the raw BLE exchange; do not double-include charger lines when sending.
- Auto-send fires on app start (MainActivity + service onCreate for the boot path) and on significant errors (BLE scan fail/disabled, charger set/read/schedule failures, cloudflared start refusals). Manual "Send Diagnostics" button bypasses the rate limit; the buffer is never cleared on send (it's the retry queue).
- Update banner shows when `versionCode` served > local `BuildConfig.VERSION_CODE`; "Download & Install" opens `apkUrl` via `ACTION_VIEW`.
- Tests: pure JVM for `LogBuffer`/payload/`isoTime`/`serverLevel`/`isNewer`; Robolectric (`@ConscryptMode(Mode.OFF)` — Conscrypt's aarch64 native is missing on this host) for persistence, latest.json parsing, and an end-to-end POST against a local `ServerSocket` mock. Keep tests server-independent (local mock only) — the live server is for manual verification.
- APK redeploy (2026-08-07, fm/exporter-pr18-update-ux-sv): `./gradlew assembleDebug` → copy to `mppt-log-server` data dir via its `./upload_apk.sh <apk> <versionName> <versionCode> [notes]` (updates `VERSION` + `/apk/latest.apk`, container bind-mounts `data/` and `VERSION`) AND copy into `/tmp/apk-downloads/` (nginx `apk-downloads` container, `downloads.lak.nz`) updating `index.html`. The mppt-logs.lak.nz public route was missing from the Cloudflare tunnel config (catch-all sent it to the lak.nz URL shortener, 404); fixed with `PUT /accounts/2c9c074e638b285304dc3f5407128bbc/cfd_tunnel/2e5cee08-6471-4601-9c35-bf73a8f4ef5f/configurations` adding `mppt-logs.lak.nz → http://0.0.0.0:8573` before the catch-all (token at `/home/ubuntu/code/tf-cloudflare/secrets/cf_token.txt` reads/writes tunnels, 403 on DNS; `tf-cloudflare/live/tunnel-config.tf` is drifted from live v164).

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
