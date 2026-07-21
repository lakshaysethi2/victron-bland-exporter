# Round-3 Review — `fix/review-blockers` @ `9dc7b0e`

Scope: verify commits `e1427b3` (parser/keycheck/BM/regex) and `9dc7b0e` (real-vector unit tests). Method: read the diffs, then **compile the actual Kotlin sources + the new test class with kotlinc 1.9.24 and execute them under JUnit 4.13.2** — no eyeball-only verdicts.

## Verdict: 🟡 Close — parser is now *proven* correct on real adverts, but one fresh regression + one test-infra gotcha keep this from green

---

## ✅ Verified by execution (not just code reading)

Ran the actual `VictronParserTest` class against kotlinc-compiled current sources:

```
JUnit version 4.13.2

...  OK (3 tests)   Time: 0.14
```

- **BlueSolar MPPT 75/15 real capture** → `ABSORPTION, 13.88V, 1.4A, 30Wh, 19W` — exact expected values ✅

- **SmartShunt 500A/50mV real capture** → `12.53V, SoC 50.0%` ✅ (layout `ttg/u16, V/s16, alarm/u16, aux/u16, aux_mode/u2, current/s22, consumed/u20, soc/u10` all correct)

- **Wrong key → rejected via keycheck byte before decrypt** ✅

- Offset table now matches protocol exactly; `[0]=0x10, [1]=0x02, [2:4]=model LE, [4]=type, [5:7]=IV LE, [7]=keycheck, [8:]=cipher` ✅

- Named-tunnel regex `CloudflaredManager.kt:60`) now `https://[^\\s]+` ✅

- Cleanups: unused `MessageDigestVICTRON_MFG_ID` gone, hex model fallback, BM field order per reference ✅

**This is the first point in the PR's life where the core value path (advert → decrypt → parse) is demonstrably correct. Good work.**

---

## 🔴 R3-1. REGRESSION: quick-tunnel regex is broken *again* (was fixed, then re-broken in `e1427b3`)

`CloudflaredManager.kt:120` (current HEAD):

```kotlin
val match = Regex("https://[a-z0-9-]+\\\\.trycloudflare\\\\.com").find(line!!)
```

Commit `57b4a55` had this **correct** `\\.`); `e1427b3` changed it **back** to `\\\\.` while fixing the other regex — whack-a-mole. As a regex this decodes to literal-backslash + any-char → **never matches** `https://x.trycloudflare.com` → quick-tunnel URL is never captured (I proved this behavior in round 1 with a live regex run). Fix (one line):

```kotlin
val match = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line!!)
```

→ This class of bug is exactly why the round-1 suggestion stands: one tiny unit test per regex.

## 🟠 R3-2. The new wrong-key test will fail in a real `./gradlew testDebugUnitTest` run

With Android's default local-unit-test stubs, any `android.util.Log` call throws `RuntimeException("not mocked")`. The wrong-key path calls `Log.w` at `VictronParser.kt:64` before returning null. Simulated exactly — result:

```
1 failure: wrong encryption key is rejected

java.lang.RuntimeException: Method w in android.util.Log not mocked

  at VictronParser.parseAdvertisement(VictronParser.kt:64)
```

(CI would be red on a fresh setup.) Fixes, any of:

1. **Preferred:** make the parser a pure function — drop `Log.wLog.e` (or return an error enum); logging from a pure parser is a code smell anyway.

2. `android { testOptions { unitTests.isReturnDefaultValues = true } }` in `app/build.gradle.kts`, or

3. Robolectric (overkill here).

Note I had to stub `android.util.Log` to compile/run at all — which also demonstrates these JVM tests currently *cannot* run without Gradle's android.jar stubs in place; with option 1 they become plain JVM tests runnable anywhere.

## 🟠 R3-3. Two `parseBatteryMonitor` subtleties (edge-case correctness)

a) **Current NA sentinel:** code checks `current != 0x1FFFFF` (max +ve of signed-22), but the on-wire NA for this field per the reference is the all-ones word `0x3FFFFF` (which sign-extends to **−1**; note the reference's own comparison `current != 0x3FFFFF` post-sign-extension can *never* fire — a latent upstream bug). If a real advert ever carries NA here, the fix branch would publish **+2097.151 A**. Safe version: check the raw unsigned value before sign-extension, or treat both `0x1FFFFF` and `-1` as NA:

```kotlin
val rawCurrent = reader.readUnsignedInt(22)
val current = BitReader.toSigned(rawCurrent, 22)   // needs the helper ported
... "battery_current" to (if (rawCurrent != 0x3FFFFF && rawCurrent != 0x1FFFFF) current / 1000.0 else null)
```

b) *`consumed_ah` sign convention:** reference negates `-x/10`, its test expects **−50.0** from raw 500 — [VE.Direct](http://VE.Direct) discharge-negative convention); the fix exports **+50.0**. Either is defensible as a metric, but diverging from the reference silently invites user confusion. Pick one deliberately and name the metric accordingly (the current SmartShunt test only asserts voltage/SoC, so this divergence is invisible to the suite — add `assertEquals(…, data["consumed_ah"])` and `data["aux_mode"]` to pin the decision down).

## 🟡 R3-4. Observations (non-blocking)

- `isVictronAdvertisement` now **requires** `[1]==0x02`; the reference only requires the `0x10` prefix. Consistent with every capture so far, but stricter than upstream — if Victron ever bumps that protocol byte, adverts get dropped at the gate. Consider accepting `0x10` and letting length+keycheck qualify the record.

- BM extra keys `alarm`, `aux`, `aux_mode`, `time_to_go_min`) flow into `MetricsStore`/UI but aren't surfaced as Prometheus metrics — fine, flag for later metric design.

- Exporter key consistency checked: `battery_voltagebattery_currentsoc_percent` line up with the new BM map ✅.

## ⬜ Still open from v1/v2 (untouched — restating only what's actionable)

| ID | Item |
|---|---|
| R5 | CTR little-endian counter for records >16 bytes (VE.Bus etc. will corrupt from byte 17) |
| M1 | Keys/tunnel token persistence `DeviceRepository` still dead code; boot restart = decrypts nothing) |
| M3 | Tunnel status UI still decorative; **Stop Tunnel** button still a no-op |
| M4 | `victron_last_seen_timestamp` = scrape time (fake); `charger_error` hardcoded 0.0 |
| M5/M6 | Boot FGS robustness (API 31+); 24/7 `SCAN_MODE_LOW_LATENCY`, no scan-failure recovery |
| S1–S3 | Bind exporter to `127.0.0.1`; scope cleartext; don't hand-build JSON |
| B3 | No `gradlew`/wrapper committed, no CI — now extra important: the repo *has tests* a fresh clone can't run |
| — | README claims MIT; no `LICENSE` file |

## Scoreboard

- v1 blockers B1, B2, R1, R2, R3, R6(named), M2 — **fixed & verified**

- v2 off-by-one — **fixed & verified by execution**

- New this round: quick-tunnel regex regression (🔴 1-liner), `Log`-not-mocked test failure (🟠 2-line config or parser purity), BM sentinel/sign subtleties (🟠 edge cases)

**Recommended: 10 minutes of fixes (R3-1, R3-2, decide on R3-3) then this branch is a solid v0.1.** After that, update PR #1 (still points at `499670e`) — or open a fresh PR from this branch — and wire up CI (`./gradlew test assembleDebug`) so the next regression gets caught by the suite instead of by me. 🙂

---

*Evidence: tests executed via kotlinc 1.9.24 + JUnit 4.13.2 against* `fix/review-blockers@9dc7b0e` *sources; "not mocked" behavior reproduced with android.jar-equivalent throwing stubs; regex regression confirmed by reading the* `57b4a55..e1427b3` *diff (correct → broken).*