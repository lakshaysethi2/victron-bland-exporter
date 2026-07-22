# Re-Review — PR #1 + `fix/review-blockers` (commit `57b4a55`)

Follow-up to the initial review (`victron-pr1-review.md`). This round I verified every claimed fix in the actual code and — crucially — tested the parser against **real captured Victron advertisements** (the `keshavdv/victron-ble` test fixtures, which are genuine on-air captures with known keys and known expected values).

## Verdict: ❌ Still request changes — do not merge either branch yet

The fix branch moves things forward and gets ~5 of 7 blockers right. However, the most important fix (parser offsets) is **off by one byte** and the parser is *still* non-functional on real devices. This isn't speculative: I ran the fix-branch parser logic on two real captured adverts and it produces garbage; the correct layout decodes them perfectly.

---

## ✅ Verified fixed (checked the code, correct)

| v1 ID | Item | Status |
|---|---|---|
| B1 | `ParsedDevice` moved to top level; imports in `MetricsStore`/`PrometheusExporter` now resolve | ✅ correct |
| R1 | Manifest: `.service.VictronBleExporterService`, `.receiver.BootReceiver` | ✅ correct |
| B2 | `data_extraction_rules.xml` + `backup_rules.xml` added (nice touch: excludes `victron_devices` sharedprefs from backups) | ✅ correct |
| M2 | Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` with `maxSdkVersion="30"` | ✅ correct |
| R6 (½) | `CloudflaredManager.kt:120` quick-tunnel regex now `\\.trycloudflare\\.com` | ✅ correct |

## ⚠️ Claimed fixed but NOT (verified in code)

- **R6 (other ½): `CloudflaredManager.kt:60` — the named-tunnel regex is still broken.** Still reads `Regex("https://[^\\\\s]+")` → Kotlin decodes to `[^\\s]` = "not backslash, not the letter s", which captures the URL **plus trailing log text up to the next 's'**. Only the trycloudflare regex was fixed. Fix to `Regex("https://[^\\s]+")`.

- **R2/R3: parser offsets — off by one (details + proof below).**

---

## 🔴 Critical: the offset fix skipped byte index 1 (the `0x02` record-protocol byte)

Real Victron adverts (after Android strips the company ID) are:

```
idx:  0    1    2  3    4      5  6    7         8…
      0x10 0x02 model(LE)  record  IV(LE)  keycheck  encrypted payload
           │      (u16)    type    (u16)  == key[0]
           └── record protocol/version byte (0x02 in every captured advert)
```

The fix branch uses `model@[1:3], type@[3], iv@[4:6], keycheck@[6], encrypted@[7:]` — everything shifted −1.

### Proof with real captured advert #1 (BlueSolar MPPT 75/15, from victron-ble's own passing test)

advert = `10 02 42 a0 01 62 07 ad ce b3 7b 60 5d 7e 0e e2 1b 24 df 5c`, key = `adeccb947395801a4dd45a2eaa44bf17`

| | True layout (reference) | Fix-branch logic |
|---|---|---|
| model | `0xA042` ("BlueSolar MPPT 75/15") | `0x4202` (junk) |
| record type | `0x01` (solar) | `0xA0` (unknown → raw bucket) |
| key check | `data[7]=0xAD == key[0]=0xAD` ✅ | passes **by accident** — their `encrypted[0]` happens to be the real keycheck byte, though their `keyCheck` var reads the wrong one |
| decrypt | `04006c050e000300130000fe` → **state=4 (ABSORPTION), 13.88V, 1.4A, 30Wh, 19W, 0.0A** — exact expected values | `6b0c70cc321489c20764279fb0` — garbage (wrong IV **and** decrypts the keycheck byte as ciphertext) |

### Proof with real captured advert #2 (SmartShunt 500A/50mV)

advert = `10 02 89 a3 02 b0 40 af 92 5d ...`, key = `aff4d099...` → true layout decodes to **12.53V, SoC 50.0%, consumed −50.0Ah, current 0A, ttg N/A** — exactly the reference's expected values. The fix branch would again yield garbage.

### Exact fix (one more byte over)

```kotlin
// [0]=0x10, [1]=0x02 protocol byte, [2-3]=model LE, [4]=recordType,
// [5-6]=IV LE, [7]=keyCheck, [8+]=ciphertext
val modelId    = ((manufacturerData[3].toInt() and 0xFF) shl 8) or (manufacturerData[2].toInt() and 0xFF)
val recordType = manufacturerData[4].toInt() and 0xFF
val iv         = manufacturerData.copyOfRange(5, 7)
val keyCheck   = manufacturerData[7].toInt() and 0xFF
val encrypted  = manufacturerData.copyOfRange(8, manufacturerData.size)

// enforce BEFORE decrypting, and do not decrypt the keycheck byte:
if (keyCheck != (key[0].toInt() and 0xFF)) return null
val decrypted = decryptAESCTR(encrypted, key, iv)
```

(minimum size check becomes `size < 8`; assert `manufacturerData[1] == 0x02` optional.)

---

## 🔴/🟠 Still open from v1 (untouched by the fix branch)

| ID | Item | Severity |
|---|---|---|
| R4 | BatteryMonitor record layout still wrong — real format: `ttg u16, voltage s16÷100, alarm u16, aux u16, aux_mode u2, current s22÷1000, consumed u20÷10 (negated), soc u10÷10` (proven with advert #2 above) | 🔴 |
| R5 | CTR counter endianness: JVM increments big-endian, Victron protocol little-endian — corrupts any record >16 bytes (e.g. VE.Bus) | 🟠 |
| M1 | Keys/tunnel token still not persisted; `DeviceRepository` still dead code; boot restart decrypts nothing | 🔴 UX |
| M3 | UI tunnel status still decorative; **Stop Tunnel** button still a no-op | 🟠 |
| M4 | `victron_last_seen_timestamp` still emits scrape time; `charger_error` hardcoded 0.0; `charge_state` mapping lossy | 🟠 |
| M5 | Boot FGS path untested/brittle on API 31+ (needs BT perms held; no keys loaded anyway) | 🟠 |
| M6 | `SCAN_MODE_LOW_LATENCY` 24/7, no scan-failure recovery, no BT state-change handling | 🟠 |
| S1–S3 | Exporter binds all interfaces, global cleartext, hand-rolled JSON | 🟡 |
| B3 | Still no `gradlew`/wrapper jar, no CI | 🟠 |
| — | No `LICENSE` file despite README claiming MIT | 🟡 |
| Nits | `VICTRON_MFG_ID` now unused; new XML files missing trailing newline | nit |

---

## The one thing that changes everything: port the captured test vectors

You already have a free, authoritative test suite — the reference repo's fixtures are real captures with known keys/values. Two unit tests would have caught *every* parser bug in both rounds:

```kotlin
// Solar
val adv  = "100242a0016207adceb37b605d7e0ee21b24df5c".hexToByteArray()
val p = VictronParser.parseAdvertisement(mac, adv, -60, "adeccb947395801a4dd45a2eaa44bf17")!!
assertEquals("ABSORPTION", p.data["charge_state"]); assertEquals(13.88, p.data["battery_voltage"] as Double, 1e-9)

// SmartShunt
val adv2 = "100289a302b040af925d09a4d89aa0128bdef48c6298a9".hexToByteArray()
val p2 = VictronParser.parseAdvertisement(mac, adv2, -60, "aff4d0995b7d1e176c0c33ecb9e70dcd")!!
assertEquals(12.53, p2.data["battery_voltage"] as Double, 1e-9); assertEquals(50.0, p2.data["soc_percent"] as Double, 1e-9)
```

## Recommended next steps

1. Apply the 1-byte offset correction above + fix `parseBatteryMonitor` to the real layout + implement LE counter (or restrict to ≤16-byte records until then).

2. Add the two fixture tests + a wrong-key rejection test to CI (port more vectors gradually: AC charger, DC/DC, VE.Bus…).

3. Fix `CloudflaredManager.kt:60` regex.

4. Then re-request review — happy to re-run the same vectors against the new build.

**Process note:** PR #1 on GitHub is still at the original broken commit `499670e`). The fixes live only on `fix/review-blockers`. Either push the corrected commits onto `arena/019f8248-victron-bland-exporter` (so the PR updates) or open the fix branch as a new PR against it.

---

*Evidence: all claims in this review were reproduced on the JVM using the exact algorithm logic from* `fix/review-blockers@57b4a55` *and fixtures from* `keshavdv/victron-ble@main` *(`tests/test_solar_charger.py`, `tests/test_battery_monitor.py`, `devices/__init__.py` confirms `model_id=data[2:4]`, `readout_type=data[4]`).*
