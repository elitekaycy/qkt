# Dukascopy Index Instruments (DXY + US equity indices) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let qkt backtest the US dollar index and the major US equity indices tick-native via the existing dukascopy integration, preserving backtest=live parity.

**Architecture:** dukascopy already serves these as free bi5 tick data; qkt's decoder is divisor-driven and `NyseCalendar` already exists. The change is data, not pipeline: add allowlist entries (`DukascopyInstrument.TABLE`) + session-calendar rules (`BacktestContext.defaultCalendars`), then verify token+divisor empirically. No new source, fetcher, decoder, store, or bars-only mode.

**Tech Stack:** Kotlin/JVM, OkHttp (already a dep), JUnit5 + AssertJ, LZMA bi5 decode (existing `DukascopyTickDecoder`).

**Issue:** #438. **Verified facts** (live bi5 fetch HTTP 200 + dukascopy-node metadata):

| qkt symbol | dukascopy token | divisor | ticks from |
|---|---|---|---|
| DXY | `DOLLARIDXUSD` | 1000 | 2017-12 |
| SPX | `USA500IDXUSD` | 1000 | 2012-01 |
| NDX | `USATECHIDXUSD` | 1000 | 2013-01 |
| DJI | `USA30IDXUSD` | 1000 | 2013-01 |
| RUT | `USSC2000IDXUSD` | 1000 | 2018-08 |

---

## File Structure

- `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrument.kt` — add the 5 index entries to `TABLE` (token differs from qkt symbol).
- `src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrumentTest.kt` — assert the new mappings.
- `src/main/kotlin/com/qkt/cli/BacktestContext.kt` — add `nyse()` calendar rules for the equity indices (DXY uses the `fxDefault` default).
- `src/test/kotlin/com/qkt/cli/BacktestCalendarsTest.kt` (new) — assert per-symbol calendar resolution.
- `src/main/kotlin/com/qkt/tools/parity/VerifyDukascopyIndexInstruments.kt` (new) — run-once empirical token+divisor check (network; not a unit test).

---

### Task 1: Map the index instruments in DukascopyInstrument.TABLE

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrument.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrumentTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `DukascopyInstrumentTest`:

```kotlin
@Test
fun `resolves the dollar index to its datafeed token`() {
    val i = DukascopyInstrument.of("DXY")
    assertThat(i.dukascopyName).isEqualTo("DOLLARIDXUSD")
    assertThat(i.priceDivisor).isEqualTo(1000L)
}

@Test
fun `resolves the us equity indices to their datafeed tokens`() {
    assertThat(DukascopyInstrument.of("SPX").dukascopyName).isEqualTo("USA500IDXUSD")
    assertThat(DukascopyInstrument.of("NDX").dukascopyName).isEqualTo("USATECHIDXUSD")
    assertThat(DukascopyInstrument.of("DJI").dukascopyName).isEqualTo("USA30IDXUSD")
    assertThat(DukascopyInstrument.of("RUT").dukascopyName).isEqualTo("USSC2000IDXUSD")
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyInstrumentTest"`
Expected: FAIL — `no dukascopy mapping for DXY`.

- [ ] **Step 3: Add the entries**

In `DukascopyInstrument.TABLE`'s `buildMap`, after the FX/JPY lines, add (the local `put(sym, divisor)` helper sets `dukascopyName = sym`; these tokens differ from the qkt symbol, so use the map's own `put(key, value)`):

```kotlin
// Index CFDs: the dukascopy datafeed token differs from the qkt symbol; all quoted to 3 dp.
put("DXY", DukascopyInstrument("DOLLARIDXUSD", 1000L))
put("SPX", DukascopyInstrument("USA500IDXUSD", 1000L))
put("NDX", DukascopyInstrument("USATECHIDXUSD", 1000L))
put("DJI", DukascopyInstrument("USA30IDXUSD", 1000L))
put("RUT", DukascopyInstrument("USSC2000IDXUSD", 1000L))
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyInstrumentTest"`
Expected: PASS.

- [ ] **Step 5: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrument.kt src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrumentTest.kt
git commit -m "feat(marketdata): map dukascopy dollar index and us equity indices"
```

---

### Task 2: Route index session calendars in the backtest

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestContext.kt` (`defaultCalendars`, ~line 214)
- Test: `src/test/kotlin/com/qkt/cli/BacktestCalendarsTest.kt` (new)

**Note:** `defaultCalendars()` is currently `private`. Change it to `internal` so the test can reach it (it is a pure factory, no I/O).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli

import com.qkt.common.TradingCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestCalendarsTest {
    @Test
    fun `equity indices resolve to the nyse calendar`() {
        val cals = BacktestContext.defaultCalendars()
        assertThat(cals.calendarFor("SPX")).isEqualTo(TradingCalendar.nyse())
        assertThat(cals.calendarFor("NDX")).isEqualTo(TradingCalendar.nyse())
        assertThat(cals.calendarFor("DJI")).isEqualTo(TradingCalendar.nyse())
        assertThat(cals.calendarFor("RUT")).isEqualTo(TradingCalendar.nyse())
    }

    @Test
    fun `the dollar index uses the fx default (it trades the fx week)`() {
        assertThat(BacktestContext.defaultCalendars().calendarFor("DXY"))
            .isEqualTo(TradingCalendar.fxDefault())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "com.qkt.cli.BacktestCalendarsTest"`
Expected: FAIL — `defaultCalendars` not accessible / SPX resolves to fxDefault.

- [ ] **Step 3: Make `defaultCalendars` internal and add the rules**

Change `private fun defaultCalendars()` to `internal fun defaultCalendars()`, and add to the `rules` list:

```kotlin
SymbolCalendars.Rule("SPX", TradingCalendar.nyse()),
SymbolCalendars.Rule("NDX", TradingCalendar.nyse()),
SymbolCalendars.Rule("DJI", TradingCalendar.nyse()),
SymbolCalendars.Rule("RUT", TradingCalendar.nyse()),
```

DXY needs no rule — it falls through to `default = TradingCalendar.fxDefault()`, which is correct (the dollar index trades the FX week, not NYSE RTH). `nyse()` for the equity indices is conservative for completeness: dukascopy's index CFDs trade extended hours, so checking only RTH coverage never false-flags a hole.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.qkt.cli.BacktestCalendarsTest"`
Expected: PASS.

- [ ] **Step 5: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/cli/BacktestContext.kt src/test/kotlin/com/qkt/cli/BacktestCalendarsTest.kt
git commit -m "feat(backtest): route index session calendars for dukascopy indices"
```

---

### Task 3: Empirically verify token + divisor (run-once tool)

A wrong divisor silently books PnL 10-100x off — the exact failure qkt's instrument guard exists to catch. The dukascopy-node metadata says divisor 1000 and the tokens fetch HTTP 200, but decode one real hour and assert the price magnitude before trusting it. Network-dependent, so a tool (like `ParityDukascopyMt5Xauusd`), not a unit test.

**Files:**
- Create: `src/main/kotlin/com/qkt/tools/parity/VerifyDukascopyIndexInstruments.kt`

- [ ] **Step 1: Write the tool**

```kotlin
package com.qkt.tools.parity

import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import com.qkt.marketdata.store.dukascopy.DukascopyTickDecoder
import com.qkt.marketdata.store.dukascopy.OkHttpHourDownloader
import java.time.LocalDate

/**
 * Sanity-checks that each newly-mapped index decodes to a price in its expected band, confirming
 * the datafeed token and divisor are correct. Run: a Tuesday in the US session is a safe hour.
 *
 * e.g. SPX in 2024-03 should decode to ~5100, DXY to ~104 — a divisor off by 10x would surface
 * as ~510 or ~51000 and fail the band check.
 */
fun main() {
    val downloader = OkHttpHourDownloader()
    val day = LocalDate.of(2024, 3, 5)
    val hour = 14
    val bands =
        mapOf(
            "DXY" to (90.0..120.0),
            "SPX" to (3000.0..7000.0),
            "NDX" to (10000.0..25000.0),
            "DJI" to (25000.0..50000.0),
            "RUT" to (1500.0..3000.0),
        )
    var ok = true
    for ((symbol, band) in bands) {
        val inst = DukascopyInstrument.of(symbol)
        val bi5 = downloader.download(inst.dukascopyName, day, hour)
        if (bi5 == null) {
            println("FAIL $symbol (${inst.dukascopyName}): no hour file"); ok = false; continue
        }
        val ticks =
            DukascopyTickDecoder.decodeRecords(
                DukascopyTickDecoder.decompress(bi5),
                hourStartMs = 0L,
                divisor = inst.priceDivisor,
                symbol = symbol,
            )
        val mid = ticks.firstOrNull()?.price?.toDouble()
        val pass = mid != null && mid in band
        println("${if (pass) "OK  " else "FAIL"} $symbol (${inst.dukascopyName}) mid=$mid expected=$band")
        if (!pass) ok = false
    }
    check(ok) { "one or more index instruments failed the price-band check" }
}
```

- [ ] **Step 2: Run the tool**

Run: `./gradlew run -PmainClass=com.qkt.tools.parity.VerifyDukascopyIndexInstrumentsKt` (or the project's tool-run convention; mirror how `ParityDukascopyMt5Xauusd` is invoked).
Expected: `OK` for all five, each mid inside its band. If a divisor is wrong, fix it in `DukascopyInstrument.TABLE` and re-run.

- [ ] **Step 3: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/tools/parity/VerifyDukascopyIndexInstruments.kt
git commit -m "chore(tools): verify dukascopy index token and divisor by decode"
```

---

### Task 4: Confirm PnL contract handling for backtest

Index PnL needs a contract/point value. The backtest's default `NoopInstrumentRegistry` books `contractSize = 1`, so PnL is reported in **index points** (1.0 move on SPX = 1.0 PnL per unit qty). That is acceptable and well-defined for daily/swing research, and the `QuoteCurrencyGuard` is satisfied (these are USD-quoted).

- [ ] **Step 1: Decide and document.** For this issue, keep `contractSize = 1` (PnL in index points) — do NOT add `InstrumentMeta` yet. Add a one-line note to the backtest docs (or the issue) stating index PnL is in points.
- [ ] **Step 2:** If a later need arises for currency-accurate index PnL (real point values), file a follow-up to add `StandardInstrumentRegistry` entries; out of scope here. No code change in this task.

---

### Task 5: End-to-end validation

- [ ] **Step 1: Run a real backtest on one index over a short range** (auto-fetch provisions the ticks):

```bash
./gradlew run --args="backtest --strategy <a-simple-SPX-strategy.qkt> --symbol BACKTEST:SPX --from 2024-03-04 --to 2024-03-08"
```

(Use any minimal strategy that references an `SPX` stream; or a smoke strategy that just reads candles.)
Expected: data provisions without an `IncompleteDataException` (or only weekend/holiday holes), candles have sane OHLC (~5100 for SPX in that window), and the run completes.

- [ ] **Step 2:** If the completeness validator over-flags (index extended hours vs `nyse()` RTH), re-run with `--allow-incomplete` and note whether a dedicated index calendar is worth a follow-up.

- [ ] **Step 3: Full build + push + PR**

```bash
./gradlew ktlintFormat && ./gradlew test
git push -u origin <branch>
gh pr create --base dev --title "feat(marketdata): backtest dukascopy dollar index and us indices" --body "...Closes #438"
```

---

## Self-Review notes

- Token + divisor are verified facts (HTTP 200 + metadata), but Task 3 re-checks by decode — do not skip it; the divisor is the one value that silently corrupts PnL.
- Naming (DXY/SPX/NDX/DJI/RUT) is the chosen convention; if changed, update Tasks 1, 2, 3, 5 consistently.
- Single-name equities are explicitly NOT in this plan (dukascopy covers major-name CFDs only; broad universe = a separate Stooq bars-only path).
