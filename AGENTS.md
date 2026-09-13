# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Repository layout: two halves that must not blur

One repo, two independent products. They share a BLE protocol and a set of
conventions, and nothing else — no shared build, no shared dependencies.

- `app/` — the **Android** exporter (Kotlin, Gradle, `./gradlew`). Runs on a phone
  beside the MPPT; serves `/metrics`, `/charger`, `/voltage` on port 5338.
- `linux/` — the **Linux host** service (Python 3.11, `python -m mppt_ble`). Runs
  on a box with BlueZ + a USB BLE adapter; serves `/metrics`, `/charger`,
  `/node/metrics` on 5338. Own `README.md`, own `requirements.txt`, own tests.
- `deploy/` — monitoring artifacts consumed by either half (`grafana-dashboard.json`).
- `docker-compose.yml` + `Makefile` — **Android build tooling only** (an Android
  build box container). They are not how you run `linux/`; that is a venv + systemd.
- `docs/`, `guide.md`, `README.md`, `SECURITY.md` — user-facing docs for both halves.

Rules that keep the halves from tangling again (this is what issue #66 is about):

- A change belongs to exactly one half. Do not "tidy" across the boundary in the
  same commit, and never make one half import or shell out to the other.
- Prefix commits and branch names with the half: `feat(linux): …`, `fix(app): …`.
  Docs/hygiene that genuinely spans both uses `chore:` / `docs:`.
- `make test` runs both suites; `make test-linux` and `make test-android` run one.
  Run the suite for the half you touched before pushing — they are independent,
  so a green Android build says nothing about `linux/`.
- Shared conventions live in prose, not in code: the charger GATT protocol
  (`charger/ChargerProtocol.kt` and `linux/mppt_ble/protocol.py` document the same
  registers), the `X-Remote-Secret` auth header, the daily-window semantics, and
  the privacy rules below. When one half changes a convention, say so in the
  other half's section here.

## Public-repo privacy (this repo is public — treat every diff as published)

Agents have pushed site details about the owner's house to this public repo
(issue #66). Assume anything committed here is permanent and indexed.

Never commit, in code, tests, docs, commit messages, branch names, issue/PR
text, logs, or screenshots:

- real hostnames, domains, tunnel URLs, or subdomains — use
  `charger.example.com`, `https://your-subdomain.trycloudflare.com`, `<phone-ip>`
- the layout or rooms of the site ("downstairs", "the lounge", where the phone or
  the laptop sits), and who lives there
- device MACs, Instant Readout keys, remote secrets, tunnel tokens, BLE PINs,
  Cloudflare account/token IDs, Wi-Fi or LAN details, IP ranges
- the owner's timezone, city, or electricity tariff as a hardcoded value — a
  schedule default is a number, not a place; read the zone from the host clock
- dated incident narratives that identify the site ("13:47 NZST on 2026-09-12 the
  bus collapsed") — describe the *mechanism* and the fix, not the address
- anything from a private review/orchestrator scratch file (`victron-pr1-review*.md`,
  `.gnhf/`, agent run logs) — those stay out of the tree

What to do instead: site-specific values come from the environment
(`MPPT_PUBLIC_HOST`, `MPPT_MAC`, `MPPT_REMOTE_SECRET`, `local.properties`
`logServerBaseUrl`) or from gitignored files under `~/.config/mppt/`. When a test
needs a host or a MAC, invent one (`charger.example.com`, `AA:BB:CC:DD:EE:FF`).
When a bug needs the real value to reproduce, put it in the issue as a redacted
description, not a literal.

Before pushing, re-read your own diff for the above. Existing occurrences in the
tree predate this rule; do not propagate them into new code, and do not
"discover" them by pasting them into commits or PR descriptions.

## Branch and PR hygiene

- Branch from current `main`, keep it short-lived, and merge it. A branch that
  outlives its PR is how this repo accumulated three stale heads carrying work
  that was never landed (issues #26 and #27).
- Squash-merge. Note that squashing a branch cut from an older `main` produces a
  commit containing *everything* that branch had and `main` lacked — review the
  resulting diff, not just the PR's own changes.
- Never force-push or rewind `main`. It has happened twice; the 2026-09-13 rewind
  to `478df6b` silently dropped already-merged work (the whole `linux/` tree, the
  #25 schedule-migration fix, the `versionCode` bump, the Python `.gitignore`
  rules). Recover from the dangling merge commit by SHA (`git fetch origin <sha>`)
  and fast-forward, rather than re-typing the work.
- Retire a branch only after its content is either merged or preserved. To keep
  the commit reachable without keeping the branch, tag the tip
  (`git tag archive/<branch> <sha> && git push origin archive/<branch>`) and then
  delete the branch. Archive tags are not a place to do further work.
- Close the issue a PR fixes, and if a PR is superseded, say which commit carries
  the work forward so nobody re-does it.

## Cloudflared on Android

- Binary is bundled as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` and executed from `nativeLibraryDir` (Android 10+ noexec on app-private dirs).
- The bundled binary MUST be the **cgo/NDK build** (dynamically linked, NEEDED libc.so, interp `/system/bin/linker64`). Static Go cloudflared resolves DNS itself from `/etc/resolv.conf` → loopback `::1`/`127.0.0.1` → `connection refused` in the app sandbox; `bindProcessToNetwork` cannot fix that (child's DNS target is a loopback address with no listener). The cgo build resolves via bionic `getaddrinfo` → netd like every native app (Go prefers cgo resolver on Android). `TunnelBinaryInspectorTest` fails the build if a static binary lands back in jniLibs.
- Rebuild command (host needs Go ≥ cloudflared's `go.mod` requirement + NDK; see git history of `fm/mppt-tunnel-child-dns` for the full recipe incl. qemu-x86_64 binfmt on arm64 hosts):
  `CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang go build -trimpath -o libcloudflared.so ./cmd/cloudflared` (from a cloudflared checkout at the shipped tag; cloudflared 2026.7.3 needs Go 1.26).
- `TunnelNetworkPrep.prepare` binds the parent process to the active network and preflights DNS via Android APIs before exec; keep the bind (correct + harmless for the parent) but know it does not cover the child's DNS.
- Named-tunnel token is dual-written to device-protected `victron_tunnel_token` prefs (readable at `LOCKED_BOOT_COMPLETED` before unlock) and the encrypted device store. Boot/sticky start restores it; the keep-alive tick also restarts cloudflared from that token if the child exits, unless the user tapped Stop (`CloudflaredManager.wasManuallyStopped`). Authenticated `POST /charger/tunnel` can paste a new token or start/stop the named tunnel from LAN so a downstairs token tap is not required. Charger schedule, remote-control secret, and Instant Readout keys live in the same device-protected prefs (`victron_charger_settings`, `victron_remote_settings`, `victron_devices_boot`) so a locked-boot start can still enforce the window, serve `/charger`, and decrypt live watts. Credential copies are reloaded on `USER_UNLOCKED`.
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. SDK via `local.properties` `sdk.dir` (gitignored). Do not add a GitHub Actions workflow on this public repo unless the push token has `workflow` scope.
- Instant Readout `/metrics` are omitted after 90s without a new advertisement (`MetricsStore.FRESH_MS`); `victron_last_seen_timestamp` is the packet time, not scrape time, and `victron_up` / `victron_devices_total` follow that window so a lost BLE link does not keep last-known watts on the dashboard.
- SmartShunt fixture `aux_mode` is 3 (`AuxMode.DISABLED` in keshavdv/victron-ble), not 0. Tunnel unit tests are under `tunnel/*Test`.
- Stale Gradle daemons: if `./gradlew` fails with `NoSuchFileException` referencing a `/tmp/fm-mppt-tunnel-*/gradle-8.7/lib/...` path, a daemon from a previous lane's run is still alive with a deleted temp distribution. Kill it (`pkill -f 'gradle-launcher-8.7'` or the PID from `ps aux | grep GradleDaemon`) and re-run; the real distribution lives under `~/.gradle/wrapper/dists/gradle-8.7-bin/`.

## Charger control over BLE

- The Instant Readout advertisements the app parses are read-only. Charger on/off is a write to the proprietary VictronConnect GATT service `306b0001-b081-4037-83dc-e59fcc3cdfd0` (chars `306b0002` control / `306b0003` commands / `306b0004` bulk), register `0x0200` device mode: `1`=on, `0`/`4`=off. Protocol + verified frame layouts are documented in `charger/ChargerProtocol.kt` (researched from Victron's BlueSolar/SmartSolar HEX protocol Rev 18, the VictronConnect APK register metadata, and the pysmartsolar / Olen solar-monitor / Mrkvak victron-linux open-source implementations).
- The device requires BLE pairing; PIN is on the sticker or `000000`. Writes are acknowledged (`WRITE_TYPE_DEFAULT`); fire-and-forget `WRITE_TYPE_NO_RESPONSE` wedges the stack so the next frame is refused. The device echoes the new mode in `08 03 19 02 00 41 <mode>` notifications. Pre-API-33 must set `char.value` before `writeCharacteristic`. Connect must count down the connected latch — otherwise every session times out after 12s even when GATT is up.
- Implementation: `charger/ChargerController.kt` (one GATT session per op, latches + dedicated HandlerThread, serialized by a Mutex; also voltage reads/writes for 0xEDEF/0xEDF7/0xEDF6/0xED4/0xDD5 with readback), `charger/ChargerSchedule.kt` + `data/ChargerScheduleStore.kt` (daily window, manual override until next boundary — `linux/mppt_ble/schedule.py` implements the *same* semantics for the Linux half, so a change to one is a change to both), `ChargerDebugLog` ring buffer (200 lines) included in Share Debug Logs. Schedule is enforced while the foreground service runs and re-applied every 10 minutes (not just at the 08:30/18:00 edges); a blank stored MAC falls back to the first fresh Instant Readout. An exact alarm at the next window boundary restarts the exporter if the process died, so 08:30/18:00 still fire after an OEM kill (USE_EXACT_ALARM so Android 14+ does not silently fall back to inexact). The service stays up after the UI is dismissed (`stopWithTask=false`, lifetime wake lock). A 15-minute keep-alive alarm (`ExporterKeepAliveAlarm`, `setExactAndAllowWhileIdle`, not `setAlarmClock`) restarts the exporter after an OEM kill between 08:30/18:00; cancel it only on the user Stop tap. `GET /charger/status` reports `batteryIgnored` so a restricted OEM can be seen from the remote page.
- Voltage settings: battery system voltage (`0xEDEF`, e.g. 12/24/48) + absorption/float/equalisation + live charger voltage + **panel/PV voltage** (`0xEDBB`, 0.01 V; Instant Readout does not carry it; `0xFFFF` = night NA) over the same GATT service. Wired to UI in `MainActivity` (Voltage Settings card + confirm dialogs), to `AppState.voltageSettings` + `PrometheusExporter` metrics (`victron_*_voltage_volts`, including `victron_panel_voltage_volts` only while fresh), and to remote `GET/POST /voltage` in `RemoteChargerHttp` (same auth secret as `/charger`; web shell at `/voltage`). Service intents: `VOLTAGE_READ`, `VOLTAGE_SET_BATTERY`, `VOLTAGE_SET_CHARGING` — see `ChargerProtocol` for frame layouts and `mppt_registers.json` provenance. Background poll (~60s, 5 min backoff after a failed GATT read) skips when a user charger op is in flight.

## Remote diagnostics + in-app updates

- App-side owner is `diag/` (`AppLog`, `Diagnostics`, `UpdateChecker`). Logs POST to `<LOG_SERVER_BASE>/api/logs` when `logServerBaseUrl` is set in gitignored `local.properties`; update check GETs that host's `/api/latest.json` (if configured) and the public GitHub `releases/latest/download/latest.json` and keeps the higher `versionCode` (a stale-but-up log host must not hide a newer GitHub APK). Server lives in the sibling `mppt-log-server` repo, not this one. Never commit private hostnames.
- `ChargerDebugLog` mirrors into `AppLog` so a Send Diagnostics tap includes the BLE exchange. Auto-send is once/hour; the button always sends. Failures stay local.
- Bump `versionCode` / `versionName` in `app/build.gradle.kts` when cutting an APK the downstairs phone should be offered.

## Remote charger control over HTTP/tunnel

- The app serves a remote charger-control surface on the same NanoHTTPD server (port 5338): `GET /` or `GET /charger` (mobile control page; `/` is the named-host landing so the tunnel hostname is not a 404), `GET /charger/status` (JSON snapshot of charger mode + daily schedule + whether the window currently wants ON and the next flip time + fresh Instant Readout watts/volts + sighted BLE devices with hasKey/wrongKey + last 20 `ChargerDebugLog` lines + tunnel status/url + app version + last GATT voltages + `lastBleAdAt` even after the 90s fresh window + `overrideUntilText` while a manual on/off is pausing the window + `batteryIgnored` + `enableTimeText`/`disableTimeText`/`nextTransitionText` (12-hour renderings from `ChargerSchedule.humanTime`, so the page and the Compose card cannot drift) + `nextTransitionAt` (epoch millis, for the page's countdown); kicks `CHARGER_READ` when mode is still unknown so a reboot does not leave the page on Unknown), `POST /charger` with `{"action":"on"|"off"|"read", "mac"?: "AA:BB:..."}` (body mac, else stored, else first live Instant Readout; `read` forwards `CHARGER_READ`), `POST /charger/schedule` with `{"enabled":bool,"enable":"HH:mm","disable":"HH:mm","mac"?}` (forwards `CHARGER_SCHEDULE_SAVE`, which also clears a manual override so the window runs again), `POST /charger/key` with `{mac, key}` (forwards `ADD_KEY`; never echoes the key), `POST /charger/tunnel` with `{token}` or `{action:"start"|"stop"}` (forwards `START_TUNNEL` / `START_SAVED_TUNNEL` / `STOP_TUNNEL`; never echoes the token), `POST /charger/scan` (forwards `RESTART_SCAN`). Authoritative code: `exporter/RemoteChargerHttp.kt` + `data/RemoteChargerStore.kt` (auth secret in plain SharedPreferences, master `enabled` switch — disabled = every /charger* route 404s). Cloud backup / device-transfer rules in `app/src/main/res/xml/` must keep excluding `victron_devices(_fallback|_boot)`, `victron_tunnel_token`, `victron_remote_settings`, `victron_charger_settings`, `victron_app_log`, and `victron_diagnostics` — otherwise Google backup ships the tunnel token, Instant Readout keys, and remote secret.
- Auth: the secret must be sent as `X-Remote-Secret` (or `Authorization: Bearer`) header; compared constant-time via `MessageDigest.isEqual`; never logged, never in URLs. The control page is a static shell (login form) because browsers can't attach custom headers to navigations — all functional routes still require the secret.
- `ChargerCommandSender.sendChargerCommand` returns Boolean and a command the service could not dispatch answers **503**, never `{"accepted":true}` (#26). `RemoteChargerHttpJson.escape` escapes every control char U+0000..U+001F per RFC 8259, so a NUL in a BLE debug line cannot break the status JSON. Strings the page paints with `textContent` must use real characters, not HTML entities (`&mdash;` shows up literally there); entities belong in markup only.
- Commands are forwarded via the same `CHARGER_SET` / `CHARGER_READ` service intents as the local UI, so remote flips and reads get the identical BLE readback verification and manual-override/schedule semantics (`performChargerSet` / `performChargerRead` in the service). The HTTP layer is a pure seam (settings/status/mac providers + `ChargerCommandSender` / `ChargerReadSender`) so `exporter/RemoteChargerHttpTest` runs on the JVM; `RemoteChargerHttpServerTest` exercises real NanoHTTPD over loopback under Robolectric (`@ConscryptMode(OFF)` — same host limitation as TunnelUrlCopyShareTest).

## Linux host service (`linux/mppt_ble`)

The other half of this repo: a Python service for a Linux box with BlueZ and a USB
BLE adapter sitting beside the MPPT. Independent of `app/` — separate deps, separate
tests, separate runtime. `linux/README.md` is the operator guide; this is the agent guide.

- Run: `python -m mppt_ble {scan,read,on,off,restart,panel,serve}`. Test: `make test-linux`
  (or `cd linux && python -m pytest`). Deps: `bleak`, `aiohttp`, `cryptography`
  (`linux/requirements.txt`). No Android SDK, no Gradle, no JDK involved.
- `serve` owns four loops: BLE scan, the yield watchdog, the `0xEDBB` panel poll, and
  the automatic ON/OFF schedule. All share one GATT lock and pause scanning before a
  write, so a new loop must follow that pattern rather than opening its own session.
- Automatic daily ON/OFF (issue #67) is `linux/mppt_ble/schedule.py`: pure logic (no BLE,
  no aiohttp, no asyncio) so `due_action()` is unit-testable, plus a JSON store. Semantics
  mirror `charger/ChargerSchedule.kt` exactly — ON 06:45/OFF 17:30 means ON inside
  `[06:45, 17:30)`, ON later than OFF is an overnight window, ON == OFF is a 24 h window
  so a degenerate config never locks the charger off, and a hand ON/OFF pauses the window
  until the next boundary. Precedence: defaults < `schedule` in `~/.config/mppt/devices.json`
  < `MPPT_SCHEDULE*` env < `serve --schedule-on/--schedule-off/--no-schedule` <
  `POST /charger/schedule`. An unrecognised `enabled` value is rejected, never coerced to
  false — reading `"sometimes"` as off would silently kill the automation.
- The watchdog must not fight the window: a pulse is off→on and always ends ON, so
  `schedule.allows_pulse()` gates it (disabled window → allow; inside the window → allow;
  outside → hold; manual override → follow the actual charger state). `/charger/status`
  reports `pulseBlocked`, folds it into `pulseWhy`, and clears `pulseCandidate` so the page
  never claims "Ready to pulse" while the window is holding it.
- `test_serve_schedule.py` boots the real aiohttp app with `client.set_mode` /
  `read_mode` / `restart.pulse` / `bleak.BleakScanner` stubbed and asserts the loop really
  flips the charger. Keep assertions wall-clock independent (use a 24 h window, a disabled
  window, or a one-minute window computed relative to now) or the suite fails at some hours.
  Config is redirected into a temp dir — never let a test read or write the developer's
  real `~/.config/mppt/`.
- Linux `mppt_ble serve` polls PV voltage on GATT `0xEDBB` at least every 10 s and exposes `victron_panel_voltage_volts` on `/metrics` while the 2-byte payload is fresh (30 s). Instant Readout has no VPV. Handshake that last worked (16:26 2026-09-12): subscribe CONTROL+SINGLE+BULK, `SAFE_INIT` + `f980` + `STREAM_ENABLE` (`060082189342102703010303`), wait ~1.5 s, then type-03 GET. **Never skip the GET** because no `08` frame arrived — that regression (18:10–08:00) left `/charger` with no panel while ads still showed watts. CONTROL `f901` is not a value. Do not send `fa80ff` or `f941` after the blob. Poll shares the charger GATT lock. On fail, exponential backoff 20–120 s. Do **not** USB-reset the Intel adapter from the poll loop (kills Instant Readout, did not recover 0xEDBB). CLI: `python -m mppt_ble panel`.
- Watchdog pulses only from panel-voltage conditions unless `watt_only_pulses` is true. This Victron is upstream of another MPPT: Instant Readout “battery” voltage is Victron output, not the cells. Auto-pulse only from averaged cascade voltages, not a single gap sample and not a hard watt cap. Need 2 min mean(`Vpv−Vout`) ≥ 2h Voc gap − (0.07 × that Voc gap), 2h Voc gap at least 2 × bus, Vout in 0.75–1.30 × bus (0xEDEF “20/40 V mode”, else 2h max out; 40 V → 30–52 V and min Voc 80 V), panel ≥ 0.85 × 2h max panel, and watts below 0.85 × this hour’s clear-sky envelope. Ignore pulse-off watts < 100 W in the 2h max. Each pulse costs a yield dip: 15 min cooldown and max 4 auto-pulses per hour (`local_mpp_max_per_hour`, overridable with `MPPT_MAX_PULSES_PER_HOUR`). Hold 30 s after the average is already high. Never leave the charger off. See `yield_config.json`. Gap samples and pulse times persist in `~/.config/mppt/watchdog.sqlite` (`MPPT_WATCHDOG_DB`). A restart hydrates the 2 min / 2 h windows. The first start after enabling the db is still empty. Hold timer is not persisted. Do not restart `mppt-ble` during daylight until that db has a 2 h fill (2026-09-12 13:47 pulsed at out 16.7 V after an empty restart).

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
