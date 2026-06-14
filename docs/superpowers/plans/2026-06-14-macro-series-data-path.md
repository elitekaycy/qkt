# Macro Series Data Path (FRED daily yields / real rates) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a strategy reference daily macro series (FRED `DGS2`/`DGS10`/`DFII10`) as read-only inputs in a backtest (and live), with point-in-time / no-look-ahead fidelity, preserving backtest=live parity.

**Architecture:** A small additive daily-cadence pipeline mirroring the dukascopy store + the seamless auto-fetch: a `FredSeriesFetcher` writes `data-root/macro/{SERIES}/{year}.csv`; a `MacroSeriesFeed` (implements `TickFeed`) replays it as one update per US business day **at the lagged release time** and merges via the existing `MergingTickFeed` alongside tick feeds; the DSL binds it under a read-only `MACRO:` prefix.

**Tech Stack:** Kotlin/JVM, OkHttp (FRED JSON), `MockWebServer` for fetch tests, JUnit5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-14-macro-series-data-path-design.md`. **Issue:** #440.

**Design decisions adopted from the spec (confirm before building):**
- Point-in-time = **fixed-lag** (ALFRED vintage-exact deferred). Value dated D is visible at **D + lag**, default the next US business day at **13:00 UTC**, configurable per series.
- DSL field = **`.value`**, with **`.close` aliased** to it so existing indicators work.
- `MACRO:` bindings are **read-only** — ordering on one (BUY/SELL/CLOSE) is a compile error.
- Starter series: `DGS2`, `DGS10`, `DFII10`.

---

## File Structure

- `src/main/kotlin/com/qkt/marketdata/store/macro/MacroSeriesStore.kt` — read/write `data-root/macro/{SERIES}/{year}.csv` (`date,value`).
- `src/main/kotlin/com/qkt/marketdata/store/macro/FredSeriesFetcher.kt` — FRED `series/observations` JSON → store (mirror `DukascopyTickFetcher` in shape).
- `src/main/kotlin/com/qkt/marketdata/macro/ReleaseSchedule.kt` — pure fixed-lag function: observation date + lag → release-time epoch ms.
- `src/main/kotlin/com/qkt/marketdata/macro/MacroSeriesFeed.kt` — `TickFeed` emitting the last-published value at each release time (step function).
- `src/main/kotlin/com/qkt/dsl/...` — `MACRO:` source binding + read-only enforcement (exact site TBD in Task 6).
- Tests alongside each, plus an end-to-end backtest test.

---

### Task 1: MacroSeriesStore (daily CSV read/write)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/macro/MacroSeriesStore.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/macro/MacroSeriesStoreTest.kt`

A `MacroPoint(date: LocalDate, value: BigDecimal)`; the store writes/reads `data-root/macro/{SERIES}/{year}.csv` rows `date,value`, sorted, idempotent (re-writing a year replaces it), and exposes `read(series, from, to): List<MacroPoint>` and `hasRange(series, from, to)`.

- [ ] **Step 1: failing test** — write two points for `DGS10`, read them back across a year boundary; assert order + values; assert `hasRange` true inside, false past the last written date.
- [ ] **Step 2:** run, expect FAIL (class missing).
- [ ] **Step 3:** implement the store (BigDecimal via `Money`; atomic write like `FileStatePersistor`'s writer).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `./gradlew ktlintFormat` + commit `feat(marketdata): add macro daily-series store`.

---

### Task 2: FredSeriesFetcher

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/macro/FredSeriesFetcher.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/macro/FredSeriesFetcherTest.kt`

Mirror `DukascopyTickFetcher`: given a series id + date range, GET FRED `series/observations` (`https://api.stlouisfed.org/fred/series/observations?series_id=DGS10&file_type=json&observation_start=...&observation_end=...`, `api_key` from `FRED_API_KEY` env), parse `observations[].date/value` (skip `value == "."`, FRED's missing marker), and write to `MacroSeriesStore`.

- [ ] **Step 1: failing test** — `MockWebServer` returns a 3-observation JSON (one with value `"."`); assert the store ends with the 2 real points, missing one skipped.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement (OkHttp, kotlinx JSON; base URL injectable for the test, as `MT5BrokerStateTest` does).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** ktlintFormat + commit `feat(marketdata): add FRED daily-series fetcher`.

---

### Task 3: ReleaseSchedule (point-in-time fixed-lag) — the correctness core

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/macro/ReleaseSchedule.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/macro/ReleaseScheduleTest.kt`

Pure function `releaseTimeMs(observationDate: LocalDate, lagBusinessDays: Int = 1, releaseUtcHour: Int = 13): Long` — the next-business-day(s) release instant in UTC. This is the load-bearing no-look-ahead rule; test it hard.

- [ ] **Step 1: failing test**

```kotlin
// A value observed Fri is released the next *business* day (Mon), not Sat.
val fri = LocalDate.of(2024, 3, 1) // Friday
val release = ReleaseSchedule.releaseTimeMs(fri)
val expected = LocalDate.of(2024, 3, 4) // Monday
    .atTime(13, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
assertThat(release).isEqualTo(expected)
// A mid-week obs (Tue) releases Wed 13:00 UTC.
val tue = LocalDate.of(2024, 3, 5)
assertThat(ReleaseSchedule.releaseTimeMs(tue))
    .isEqualTo(LocalDate.of(2024, 3, 6).atTime(13, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
```

- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement (advance `lagBusinessDays` skipping Sat/Sun; `.atTime(releaseUtcHour,0)` UTC → epoch ms). Holidays not modeled in v1 — note it; a value simply releases a day "early" relative to a holiday, still never look-ahead.
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** ktlintFormat + commit `feat(marketdata): add macro release schedule (fixed lag)`.

---

### Task 4: MacroSeriesFeed (TickFeed, point-in-time replay)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/macro/MacroSeriesFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/macro/MacroSeriesFeedTest.kt`

Implements `TickFeed` (`next(): Tick?`, `close()`). Given a `qktSymbol` (e.g. `MACRO:DGS10`), the stored points, and a `TimeRange`, emit a `Tick(symbol = qktSymbol, price = value, timestamp = ReleaseSchedule.releaseTimeMs(date), volume = null)` for each point **whose release time falls in range**, in release-time order. The merge (`MergingTickFeed`) interleaves these with symbol tick feeds by timestamp.

- [ ] **Step 1: failing test** — points for D=Mon..Wed; query a range covering Tue 12:00. Assert the only tick seen by Tue 12:00 is **Monday's** value released Tue 13:00? No — released Tue 13:00 > 12:00, so at Tue 12:00 the strategy has only **Friday's** value (released Mon). Encode the exact look-ahead assertion: a tick with Tuesday's value must have timestamp ≥ Wed 13:00 UTC.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement (map points → release-stamped ticks via `ReleaseSchedule`, filter to range, sort, iterate).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** ktlintFormat + commit `feat(marketdata): add point-in-time macro series feed`.

---

### Task 5: Wire MACRO feeds into the backtest (merge + auto-provision)

**Files:**
- Modify: the backtest feed assembly (read `src/main/kotlin/com/qkt/backtest/Backtest.kt` + `BacktestContext.kt` to find where per-symbol `TickFeed`s are built and merged via `MergingTickFeed`).
- Modify: `BacktestDataProvisioner` (or a sibling) to auto-fetch macro series + warmup history before the run.

For each declared stream whose broker prefix is `MACRO`, build a `MacroSeriesFeed` from the store (auto-provisioning via `FredSeriesFetcher` if absent, including warmup history before `from`) instead of a `LocalMarketSource` tick feed, and add it to the `MergingTickFeed` set.

- [ ] **Step 1: failing test** — a `MergingTickFeed` of a fake symbol tick feed + a `MacroSeriesFeed` yields ticks in global timestamp order, macro ticks interleaved at their release times. (Read `MergingTickFeed`/`Backtest` first to match the assembly seam.)
- [ ] **Step 2-4:** implement the routing + provisioning; run to green.
- [ ] **Step 5:** ktlintFormat + commit `feat(backtest): route MACRO streams through the macro feed`.

---

### Task 6: DSL MACRO: binding (read-only, .value / .close)

**Files:**
- Modify: DSL stream binding + expr compilation (grep `qktSymbol`/`HubKey`/stream-source handling in `src/main/kotlin/com/qkt/dsl/compile/`; `MACRO:DGS10` already parses to `HubKey(broker="MACRO", symbol="DGS10")`).

- [ ] **Step 1: failing tests** — (a) `real10y.value` and `real10y.close` both compile and read the feed's price; (b) a rule with `BUY real10y` (or any order action on a `MACRO:` stream) fails compilation with a clear "macro series is read-only" message.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement `.value` (alias `.close`) for `MACRO:` streams + the read-only ordering guard at compile time.
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** ktlintFormat + commit `feat(dsl): read-only MACRO series binding with .value`.

---

### Task 7: End-to-end + parity

**Files:**
- Test: `src/test/kotlin/com/qkt/backtest/MacroSeriesBacktestTest.kt`

- [ ] **Step 1:** a backtest with `gold = BACKTEST:XAUUSD` ticks (fixture) + `real10y = MACRO:DFII10` (fixture store) runs a rule referencing `real10y.value`; assert (a) deterministic output across two seeded runs, (b) no decision uses a series value before its release time (the look-ahead guard end-to-end).
- [ ] **Step 2:** ktlintFormat, full `./gradlew test`, push, `gh pr create --base dev ... Closes #440`.

---

## Notes / open items to confirm at build time
- Exact backtest feed-assembly seam (Task 5) and DSL binding site (Task 6) are referenced by pattern; read those files first — they are the only spots not pinned to a line here.
- Holiday calendar for releases is omitted in v1 (weekends only); a holiday makes a value release one business day "early" but never creates look-ahead. Revisit if a strategy proves sensitive.
- ALFRED vintage-exact and intraday-release timing are explicitly deferred (see spec §7).
