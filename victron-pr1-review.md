# Code Review — PR #1: `feat: complete victron-ble-exporter Android app skeleton`

**Repo:** lakshaysethi2/victron-bland-exporter · **Commit:** `499670e` · **Size:** 29 files, +1,682 lines

**Reviewed locally** (cloned `pr-1` branch), parser claims cross-checked against the reference implementation `keshavdv/victron-ble`](https://github.com/keshavdv/victron-ble), and the crypto/regex claims verified empirically on the JVM (see Appendix).

## Verdict: ❌ Request changes — does not build; could not produce a single metric even after build fixes

The architecture and structure are genuinely good for a skeleton (clean separation: parser / exporter / tunnel / service / UI; lightweight deps; sensible manifest planning). However:

1. **The code does not compile** (unresolved import + missing resources).

2. **The manifest registers both components under wrong package names** → the service/receiver classes can't be found at runtime.

3. **The BLE parser can never parse a real advertisement** (off-by-2 byte layout: Android strips the company ID; the parser expects it).

4. **Decryption silently produces garbage data** (key-check byte never verified; CTR never throws).

5. **The SmartShunt/BatteryMonitor parser does not match the real record format.**

6. Several README/commit claims (encrypted key storage, working tunnel URL, boot persistence) are not actually wired up.

None of these are deep design flaws — it's fixable — but this is well short of "ready for real device testing" as the commit claims.

---

## 🔴 Blockers — build fails

### B1. Unresolved import: `ParsedDevice` is nested, imported as top-level

`parser/VictronParser.kt:18` declares `data class ParsedDevice` **inside** `object VictronParser`, but both consumers import it as a top-level package member:

- `exporter/MetricsStore.kt:3` — `import com.lakshaysethi.victronbleexporter.parser.ParsedDevice`

- `exporter/PrometheusExporter.kt:4` — same

→ `Unresolved reference: ParsedDevice`, module fails to compile.

**Fix:** move `ParsedDevice` to top level in the parser package (preferred), or import `...parser.VictronParser.ParsedDevice`.

### B2. Manifest references resources that don't exist

`AndroidManifest.xml:32–33`:

```xml
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/backup_rules"
```

`app/src/main/res/xml/` contains only `file_paths.xml`. → aapt2 link error: *resource xml/data_extraction_rules not found*.

**Fix:** add both files (trivial XML), or drop the attributes.

### B3. The repo cannot be built as instructed

No `gradlew` / `gradlew.bat` scripts, and `gradle-wrapper.jar` is a placeholder text file (`gradle/wrapper/gradle-wrapper.jar.placeholder.txt`). The README says `./gradlew assembleDebug` — that command cannot work from a fresh clone. Also there is **no CI**, so none of the above was caught. `.gitignore` even un-ignores `gradle-wrapper.jar`, i.e. committing it was intended.)

---

## 🔴 Blockers — runtime / functional (would fail on real hardware)

### R1. Manifest component names point to non-existent classes

`AndroidManifest.xml:55,61`:

```xml
<service android:name=".VictronBleExporterService" ... />
<receiver android:name=".BootReceiver" ... />
```

Actual FQCNs are `...victronbleexporter.service.VictronBleExporterService` and `...receiver.BootReceiver`. `.VictronBleExporterService` resolves to the app-package root → `ClassNotFoundException` the moment "Start Exporter" is tapped (and on every boot).

**Fix:** `.service.VictronBleExporterService` / `.receiver.BootReceiver`.

### R2. Parser byte layout assumes the company ID is present — Android strips it (the app can never parse anything)

`VictronParser.kt:26–29, 38–46` expects mfg-data as `[E1 02][10][model 2][type][iv 2][keycheck][cipher…]`.

But `ScanRecord.getManufacturerSpecificData(0x02E1)` (service, `VictronBleExporterService.kt:115`) returns the payload **without** the 2-byte company ID (same as bleak, which the reference uses), i.e. `[10][model][type][iv][keycheck][cipher…]`. Verified empirically (Appendix A1): `isVictronAdvertisement()` returns `false` for a real advertisement, so every advert lands in the "no key or parse fail" branch and **zero devices ever appear**.

Fix = shift every offset by −2 (correct layout: `prefix@0`, `model@1-2`, `type@3`, `iv@4-5`, `keycheck@6`, `cipher@7+`). Note the scan *filter* at `VictronBleExporterService.kt:108` (`byteArrayOf(0x10)`) is already correct for the stripped layout — only the parser is wrong.

### R3. Key-check byte is read but never used → wrong keys silently produce plausible garbage

`VictronParser.kt:45,67-69` reads `keyCheck` and then explicitly does nothing with it ("assume success if decrypt didn't throw"). AES-CTR is a stream mode — **decryption never throws on a wrong key** (verified, Appendix A3); you just get plausible-looking random metrics published to Prometheus. The reference implementation rejects: `if encrypted_data[0] != key[0]: raise AdvertisementKeyMismatchError`.

**Fix:** `if (keyCheck != key[0].toInt() and 0xFF) return null` before decrypting.

### R4. Battery monitor (record `0x02`) field layout doesn't match the real protocol

`VictronParser.kt:135-150` reads: `aux u16, voltage s16, current s16, power s16, consumed s16, soc u16, ttg u16`.

The actual SmartShunt/BMV record (reference `battery_monitor.py`):

| field | bits | notes |
|---|---|---|
| remaining_mins (ttg) | u16 | `0xFFFF` = N/A |
| voltage | s16 | ÷100 V |
| alarm | u16 | bitmask |
| aux | u16 | (depends on aux_mode) |
| aux_mode | u2 | |
| current | **s22** | ÷1000 A |
| consumed_ah | **u20** | ÷10, negated |
| soc | **u10** | ÷10 % |

So even after R1–R3 are fixed, SmartShunt data will be garbage. The claimed "basic SmartShunt" support doesn't exist yet.

### R5. CTR counter endianness breaks any record longer than 16 bytes

`VictronParser.kt:88-95` uses the JVM's `AES/CTR/NoPadding`, which increments the 16-byte counter **big-endian**. The Victron scheme (reference: `Counter.new(128, initial_value=iv, little_endian=True)`) increments it **little-endian**. Verified (Appendix A4): block 0 (bytes 0–15) matches, bytes 16+ do not. Solar/BM payloads fit in one block so they survive, but any multi-block record type (VE.Bus, etc.) decrypts corrupt from byte 17 on — a nasty latent bug for "more device types" the README invites.

### R6. Both tunnel-URL regexes are broken by over-escaping

`CloudflaredManager.kt:60`: `Regex("https://[^\\\\s]+")` — the Kotlin string decodes to regex `[^\\s]`, i.e. "not backslash and not the letter **s**". Verified (Appendix A5): on a real log line it captures `https://word-word-word.trycloudflare.com regi` — URL plus trailing junk, cut at the first `s`.

`CloudflaredManager.kt:120`: `Regex("https://[a-z0-9-]+\\\\.trycloudflare\\\\.com")` contains literal backslashes → **can never match** → quick-tunnel URL is never found and `tunnelUrl` stays `null` forever.

**Fix:** `Regex("https://[^\\s]+")` and `Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")`.

---

## 🟠 Major issues

- **M1. Keys are never persisted; `DeviceRepository` is dead code.** The commit message lists "Encrypted storage for keys" as done; in reality the service keeps keys in an in-memory `deviceKeys` map (`VictronBleExporterService.kt:34-42`), `DeviceRepository` (EncryptedSharedPreferences — a fine implementation) is never instantiated anywhere, and nothing is loaded in `onCreate`. After reboot/process death the app scans but decrypts nothing until keys are re-typed. Same for the tunnel token. (The README honestly lists it as TODO — the commit message contradicts it.)

- **M2. Missing legacy Bluetooth permissions for API 26–30.** Only `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+ perms) are declared. `minSdk = 26`, and on Android 8–11 `startScan` needs `BLUETOOTH`/`BLUETOOTH_ADMIN` with `maxSdkVersion="30"`. The app's core purpose is "run on an old phone" — it currently *can't scan* on any old phone.

- **M3. UI tunnel status is entirely decorative.** `tunnelStatus`/`tunnelUrl` Compose state is never updated from the service (source comment admits "we fake"); the **Stop Tunnel** button has an empty `onClick` (`MainActivity.kt` ~line 235). Service↔UI status needs a binder, broadcasts, or a shared singleton the UI reads.

- **M4. `victron_last_seen_timestamp` is fake** (`PrometheusExporter.kt:48`): it emits scrape time, not when the advert was received — `ParsedDevice` doesn't even carry a timestamp. As-is it's an always-current, useless metric; add a real timestamp at parse time. Similarly `victron_charger_error` is hardcoded `0.0` (line 63) and `charge_state` is mapped string→number round-trip that collapses everything except BULK/ABSORPTION/FLOAT/OFF to −1; keep the raw numeric value.

- **M5. Boot path is brittle.** Even after R1/M1 fixes: on API 31+ starting a `connectedDevice` FGS from `BOOT_COMPLETED` requires BT permissions already granted (else `SecurityException`), and the service starts with zero keys. It'll "run" and export nothing. Needs persisted keys + a "enabled" flag + try/catch with log.

- **M6. Battery/runtime hygiene.** `SCAN_MODE_LOW_LATENCY` 24/7 with no retry on `onScanFailed`, no `BluetoothAdapter` state-change handling, no backoff — harsh for an always-on box; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared but never requested in code (and carries Play-store policy weight — decide deliberately).

---

## 🟡 Security & privacy

- **S1. `/metrics` is unauthenticated on all interfaces.** NanoHTTPD binds `0.0.0.0:9100`; anyone on the LAN (or with the trycloudflare URL) can read your battery/solar state (a decent occupancy/burglary signal). Since cloudflared proxies to `localhost`, **bind NanoHTTPD to `127.0.0.1`** — zero functional loss; and document Cloudflare Access for the named tunnel.

- **S2. `usesCleartextTraffic="true"` globally** (manifest line 39). Scope it via a network-security-config to localhost only.

- **S3. `/devices` JSON is hand-built** (`PrometheusExporter.kt:97-110`) with unescaped string interpolation — a quote in any value/model name makes invalid JSON. Use org.json/serialization.

---

## 🟢 Clean / well-done

- Solar charger (record `0x01`) bit layout, field widths, scalings and `0x7FFF/0xFFFF` sentinels match the reference exactly; the `BitReader` is a correct LSB-first port — **verified round-trip on a synthetic advert** (Appendix A2: `V=12.4 I=8.5 yield=1230 solar=234 load=1.2` decoded perfectly once crypto was fed correctly).

- The single-block AES-CTR keystream construction is correct (matching `iv_lo, iv_hi, 0…`) — decryption math is right in principle (App. A2).

- Scan filter/manufacturer-ID approach, NanoHTTPD choice, foreground-service + notification flow, EncryptedSharedPreferences choice, `.gitignore` hygiene (keystore/token excludes), and the README's "bring-your-own cloudflared `.so`" instructions are all solid.

## 🔵 Nits

- Dead/unused: `VictronDevice` data class, `BitReader.hasMore()/skip()` (`skip` even has a dead `remaining` var), `MessageDigest`/`ByteBuffer` imports, `activity_main.xml`, KSP plugin in root build script, unused `how_to_get_key` strings, `MetricsStore.clear()`, duplicate port constant.

- `Manifest.permission.FOREGROUND_SERVICE` in the runtime permission request (it's an install-time permission — noise); `neverForLocation` flag + requesting `ACCESS_FINE_LOCATION` is contradictory and Play-visible; `getModelName()` falls back to decimal `Victron-41816` rather than hex; enum `values()` deprecated; `android.nonFinalResIds=false` is a deprecated flag; compileSdk 35 with AGP 8.5.1 will warn.

- README: mentions "Ktor" (not used), lists files that don't exist (`Device.kt`, `ui/`), and claims MIT license but **no `LICENSE` file is committed**.

---

## Suggested merge checklist

1. Move `ParsedDevice` to top level; add `data_extraction_rules.xml`/`backup_rules.xml` → build green.

2. Fix manifest class names (`.service.…`, `.receiver.…`).

3. Parser: shift offsets for stripped company ID; **verify `keyCheck == key[0]`**; port the real `0x02` layout; handle LE counter for multi-block records.

4. **Add unit tests with real captured advertisements** (the reference repo has fixtures + tests — port `test_solar_charger.py`/`test_battery_monitor.py` vectors). This one step would have caught R2–R5.

5. Wire `DeviceRepository` into service `onCreate` + UI add-key; persist tunnel token.

6. Add `BLUETOOTH`/`BLUETOOTH_ADMIN` with `maxSdkVersion="30"`; drop `neverForLocation`/FINE contradiction.

7. Fix both regexes; plumb tunnel status to UI; make Stop Tunnel work.

8. Bind exporter to `127.0.0.1`; scope cleartext to localhost; parse-time timestamps.

9. Commit the real Gradle wrapper (or `./gradlew wrapper`) + a GitHub Actions build; add `LICENSE`.

---

## Appendix — verification evidence (JVM, exact PR algorithm re-implemented)

Synthetic advertisement: key[0]`0x0A`, nonce `EF BE`, solar record `state=5, err=0, 12.40V, 8.5A, 1230Wh, 234W, 1.2A`, encrypted per reference (LE counter), presented as Android delivers it (company ID stripped).

```
A1. prIsVictronAdvertisement(androidMfgData) = false        → parse() returns null; nothing ever appears (R2)

A2. decrypt ok = true                                        → PR crypto correct for ≤16-byte records IF offsets fixed
    parsed: state=5 err=0 V=12.4 I=8.5 yield=1230W solar=234W load=1.2A

A3. wrong-key decrypt produced 12 bytes of garbage without exception   → keycheck must be enforced (R3)

A4. keystream identical for bytes 0-15 : true
    keystream identical for bytes 16+  : false               → BE vs LE counter (R5)

A5. PR named-tunnel regex match:  https://word-word-word.trycloudflare.com regi   (garbage capture)
    PR quick-tunnel regex match:  <none>                      (never matches)
    fixed regex match:          https://word-word-word.trycloudflare.com
```

Reference implementation consulted: `victron_ble/devices/base.py` (`parse_container`, `decrypt` with `Counter.new(128, initial_value=iv, little_endian=True)`, key-byte check raising `AdvertisementKeyMismatchError`), `battery_monitor.py`, `solar_charger.py` on `keshavdv/victron-ble@main`.

---

**End of review.** Ready for fixes on a follow-up branch.