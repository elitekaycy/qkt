# Backtest Replay From Fetched OHLC Bars (#214) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `qkt fetch` + `qkt backtest` produce trades for bars-only venues (crypto) by synthesizing a tick feed from the fetched OHLC bars and driving the existing tick pipeline.

**Architecture:** A shared `candleToTicks(candle)` emits four sub-bar ticks (O→L→H→C) that re-aggregate to the exact original candle; `BarTickFeed` flattens that over `source.bars()`; the backtest feed selection prefers real ticks and falls back to `BarTickFeed`; warmup reuses `candleToTicks`; `BacktestCommand` passes the prefixed `qktSymbol`. No engine/live change — same single tick pipeline, so backtest=live holds.

**Tech Stack:** Kotlin, the qkt market-data/backtest modules (`com.qkt.marketdata`, `com.qkt.backtest`), JUnit 5 + AssertJ.

Spec: `docs/superpowers/specs/2026-06-03-issue214-bar-replay-backtest-design.md`

---

## File Structure

- `marketdata/source/BarTickFeed.kt` (new) — top-level `candleToTicks(candle): List<Tick>` + `class BarTickFeed(bars: Sequence<Candle>) : TickFeed`.
- `app/IndicatorWarmer.kt` (modify) — warmup emits `candleToTicks(candle)` instead of one close-tick.
- `cli/BacktestCommand.kt` (modify) — use `StreamDecl.qktSymbol` (prefixed) for the request.
- `backtest/Backtest.kt` (modify) — relax the `TICKS`-only gate; per-symbol feed selection (ticks else `BarTickFeed`).
- `docs/how-to/fetch-data.md` (modify) — correct the false "backtests read from that store automatically" claim.
- Tests: `marketdata/source/BarTickFeedTest.kt`, `app/IndicatorWarmerOhlcTest.kt` (or extend an existing warmup test), `cli/BacktestCommandSymbolTest.kt` (or extend), `backtest/BarBacktestTest.kt`.

Run tests with `./gradlew --offline test --tests "<FQN>"`; ktlint `./gradlew --offline ktlintFormat ktlintCheck`. Do NOT run the full build.

Reference types (already exist):
- `Tick(symbol: String, price: BigDecimal, timestamp: Long, volume: BigDecimal? = null, ...)`.
- `Candle(symbol, open, high, low, close, volume: BigDecimal, startTime: Long, endTime: Long, ...)` — `[startTime, endTime)`.
- `interface TickFeed { fun next(): Tick?; fun close() {} }`; `SequenceTickFeed(seq: Sequence<Tick>)`; `MergingTickFeed(feeds: List<TickFeed>)`.
- `enum MarketSourceCapability { LIVE_TICKS, BARS, TICKS }`; `MarketRequest(symbols: List<String>, from: Instant?, to: Instant?)`.

---

## Task 1: `candleToTicks` + `BarTickFeed`

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/BarTickFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/source/BarTickFeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarTickFeedTest {
    private fun candle(o: String, h: String, l: String, c: String, start: Long, end: Long) =
        Candle("BYBIT_SPOT:BTCUSDT", BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c),
            BigDecimal("10"), start, end)

    @Test
    fun `candleToTicks emits O L H C in order with strictly increasing in-window timestamps`() {
        val cdl = candle("100", "110", "90", "105", 0L, 300_000L)
        val ticks = candleToTicks(cdl)
        assertThat(ticks.map { it.price }).containsExactly(
            BigDecimal("100"), BigDecimal("90"), BigDecimal("110"), BigDecimal("105"),
        )
        assertThat(ticks.map { it.timestamp }).isSorted()
        assertThat(ticks.first().timestamp).isGreaterThanOrEqualTo(0L)
        assertThat(ticks.last().timestamp).isLessThan(300_000L)
        // volume only on the close tick, summing to the candle's volume
        assertThat(ticks.map { it.volume ?: BigDecimal.ZERO }.reduce(BigDecimal::add))
            .isEqualByComparingTo("10")
    }

    @Test
    fun `the four ticks re-aggregate to the original candle`() {
        val cdl = candle("100", "110", "90", "105", 0L, 300_000L)
        // Aggregate the synthetic ticks at the same timeframe; the rebuilt candle must equal the input.
        val agg = CandleAggregator(TimeWindow.parse("5m"))   // confirm the exact TimeWindow API
        val rebuilt = mutableListOf<Candle>()
        for (t in candleToTicks(cdl)) agg.onTick(t).let { /* collect emitted candle if returned */ }
        // Drive one extra tick past endTime to force the candle to close, then assert OHLC equals input.
        // (Match CandleAggregator's actual emit API — onTick may return a Candle? or call a sink.)
        // assert rebuilt.single() has open=100 high=110 low=90 close=105 volume=10
    }

    @Test
    fun `BarTickFeed flattens candles into ticks in chronological order`() {
        val feed = BarTickFeed(sequenceOf(
            candle("100", "110", "90", "105", 0L, 300_000L),
            candle("105", "108", "104", "107", 300_000L, 600_000L),
        ))
        val out = generateSequence { feed.next() }.toList()
        assertThat(out).hasSize(8)
        assertThat(out.map { it.timestamp }).isSorted()
    }
}
```

(Read `src/main/kotlin/com/qkt/candles/CandleAggregator.kt` and `TimeWindow.kt` for the exact emit API and `TimeWindow` construction, and finish the re-aggregation assertion against the real API — the property to prove is `(open,high,low,close,volume)` of the rebuilt candle equals the input.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.marketdata.source.BarTickFeedTest"`. Expected: compile error (`candleToTicks`/`BarTickFeed` missing).

- [ ] **Step 3: Implement** `BarTickFeed.kt`:

```kotlin
package com.qkt.marketdata.source

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

/**
 * Synthesizes four sub-bar ticks from one OHLC [candle], in the order Open, Low, High, Close.
 *
 * The order is the pessimistic convention: the adverse extreme (Low, for a long) is reached before
 * the favorable one, so a bar-based backtest won't overstate a risk-managed strategy. The four ticks
 * fall at strictly increasing timestamps inside the candle's `[startTime, endTime)` window and
 * re-aggregate (via CandleAggregator) to exactly this candle (first=open, max=high, min=low,
 * last=close); volume sits on the close tick so the aggregated volume matches.
 *
 * The intra-bar High/Low order is unknowable from OHLC alone — this is a documented approximation.
 */
fun candleToTicks(candle: Candle): List<Tick> {
    val span = candle.endTime - candle.startTime
    val step = (span / 4).coerceAtLeast(1)
    return listOf(
        Tick(candle.symbol, candle.open, candle.startTime),
        Tick(candle.symbol, candle.low, candle.startTime + step),
        Tick(candle.symbol, candle.high, candle.startTime + 2 * step),
        Tick(candle.symbol, candle.close, candle.endTime - 1, volume = candle.volume),
    )
}

/** A [TickFeed] over OHLC bars: each candle becomes the four [candleToTicks] in chronological order. */
class BarTickFeed(
    bars: Sequence<Candle>,
) : TickFeed {
    private val iter = bars.flatMap { candleToTicks(it).asSequence() }.iterator()

    override fun next(): Tick? = if (iter.hasNext()) iter.next() else null
}
```

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. Then ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/source/BarTickFeed.kt src/test/kotlin/com/qkt/marketdata/source/BarTickFeedTest.kt
git commit -m "feat(marketdata): synthesize OHLC ticks via BarTickFeed"
```

---

## Task 2: Warmup uses `candleToTicks`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt` (the `warmupSymbol` loop, ~lines 50-79)
- Test: `src/test/kotlin/com/qkt/app/IndicatorWarmerOhlcTest.kt`

Current loop reads `source.bars(...)` and emits ONE synthetic close-tick per candle (`Tick(symbol, price = candle.close, timestamp = candle.endTime - 1, volume = candle.volume)` → `pipeline.ingestForWarmup(tick)`). That aggregates to a degenerate `O=H=L=C` candle, so range indicators (`atr`) warm with zero range.

- [ ] **Step 1: Write the failing test** — warm an `atr`-style range indicator from bars whose high≠low and assert the warmed value is non-zero (degenerate close-only warmup gives zero).

```kotlin
// src/test/kotlin/com/qkt/app/IndicatorWarmerOhlcTest.kt
// Build a strategy/indicator that exposes a range-based value (e.g. atr(gold, n)); warm it from a
// stub MarketSource whose bars() returns candles with a real high-low spread; assert the indicator's
// post-warmup value is > 0. Read an existing IndicatorWarmer test for the harness (stub source,
// pipeline, how warmed indicator values are read).
```

(Read `src/test/kotlin/com/qkt/app/IndicatorWarmer*Test.kt` for the existing warmup test harness — reuse its stub `MarketSource` + pipeline setup; assert against a range indicator.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.app.IndicatorWarmerOhlcTest"`. Expected: FAIL (warmed range is 0 with the close-only synthesis).

- [ ] **Step 3: Implement** — in `IndicatorWarmer.warmupSymbol`, replace the single-tick emission with `candleToTicks`:

```kotlin
for (candle in source.bars(symbol, bars.window, range)) {
    for (tick in com.qkt.marketdata.source.candleToTicks(candle)) {
        require(tick.timestamp < now.toEpochMilli()) {
            "look-ahead bias: warmup tick beyond now=$now; symbol=$symbol"
        }
        pipeline.ingestForWarmup(tick)
    }
}
```

(Match the existing look-ahead guard message/variable names already in the file; the guard now applies to every synthesized tick. Add the `candleToTicks` import.)

- [ ] **Step 4: Run to verify pass** — same command; also run any existing IndicatorWarmer tests to confirm no regression. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/IndicatorWarmer.kt src/test/kotlin/com/qkt/app/IndicatorWarmerOhlcTest.kt
git commit -m "fix(warmup): seed indicators from full OHLC, not close-only"
```

---

## Task 3: `BacktestCommand` passes the prefixed `qktSymbol`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` (~lines 53-67, the `declaredSymbols` build)
- Test: `src/test/kotlin/com/qkt/cli/BacktestCommandSymbolTest.kt`

Current: `val declaredSymbols = ast.streams.map { it.symbol }.distinct()` — bare `BTCUSDT`, dropping the broker. `StreamDecl.qktSymbol` (`StrategyAst.kt:42`) = `"$broker:$symbol"` is the prefixed form `source.bars()`/`ticks()` need.

- [ ] **Step 1: Write the failing test** — assert that for a strategy declaring `btc = BYBIT_SPOT:BTCUSDT EVERY 5m`, the backtest's `MarketRequest.symbols` contains `"BYBIT_SPOT:BTCUSDT"`, not `"BTCUSDT"`. (Read `BacktestCommand` for the cleanest seam — if the request isn't directly testable, factor the symbol resolution into a small internal function and test that; or assert via a stub source that records the symbol passed to `bars()`.)

- [ ] **Step 2: Run to verify it fails** — Expected: FAIL (bare symbol).

- [ ] **Step 3: Implement** — change `ast.streams.map { it.symbol }` to `ast.streams.map { it.qktSymbol }` in `declaredSymbols`. Ensure the `--symbols` override comparison uses the same prefixed list, and that whatever maps streams→request downstream keeps the prefix.

- [ ] **Step 4: Run to verify pass** — same command + run existing `BacktestCommand` tests for no regression. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BacktestCommand.kt src/test/kotlin/com/qkt/cli/BacktestCommandSymbolTest.kt
git commit -m "fix(backtest): pass broker-prefixed qktSymbol to the market request"
```

---

## Task 4: Feed selection + end-to-end fetch→backtest

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt` (`fromSource`, lines 126-162)
- Modify: `docs/how-to/fetch-data.md`
- Test: `src/test/kotlin/com/qkt/backtest/BarBacktestTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// src/test/kotlin/com/qkt/backtest/BarBacktestTest.kt
// 1. A MarketSource exposing only BARS (capabilities = {BARS}) whose bars() returns a few candles
//    with close>0 → Backtest.fromSource(...) of a strategy with `WHEN btc.close > 0 THEN BUY btc`
//    produces > 0 trades. (Today this throws "requires TICKS".)
// 2. A source with TICKS + real ticks → still uses the tick feed (assert a marker proving ticks,
//    not bar-synthesis, drove it — e.g. a tick at a price no candle close has).
// 3. (Stop fidelity) A bars-only source with a candle whose low pierces a stop level → the stop
//    fills at/around the low (proving intra-bar OHLC synthesis, not close-only).
// Build the source as a small in-test stub implementing MarketSource (capabilities + bars()/ticks()).
// Read an existing Backtest.fromSource test for the strategy/compile + trade-count harness.
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.backtest.BarBacktestTest"`. Expected: FAIL (test 1 throws "requires TICKS").

- [ ] **Step 3: Implement the feed selection** in `Backtest.fromSource`:

Relax the capability gate (line 138):
```kotlin
require(
    MarketSourceCapability.TICKS in source.capabilities ||
        MarketSourceCapability.BARS in source.capabilities,
) { "Backtest requires a MarketSource with TICKS or BARS; ${source.name} has ${source.capabilities}" }
```

Replace the per-symbol feed build (line 144-145) with a selector:
```kotlin
val perSymbolFeeds: List<TickFeed> =
    request.symbols.map { sym -> replayFeed(source, sym, range, candleWindow) }
```

Add the private helper (in the companion):
```kotlin
private fun replayFeed(
    source: MarketSource,
    symbol: String,
    range: TimeRange,
    window: TimeWindow?,
): TickFeed {
    val caps = source.capabilities
    if (MarketSourceCapability.TICKS in caps) {
        val iter = source.ticks(symbol, range).iterator()
        if (iter.hasNext()) {
            val first = iter.next()
            return SequenceTickFeed(sequenceOf(first) + iter.asSequence())
        }
    }
    require(MarketSourceCapability.BARS in caps) {
        "no tick data for $symbol and source ${source.name} has no BARS to synthesize from"
    }
    requireNotNull(window) {
        "bar-based backtest for $symbol needs a candle window (timeframe) — pass candleWindow"
    }
    return BarTickFeed(source.bars(symbol, window, range))
}
```

This prefers real ticks (MT5 unchanged) and falls back to `BarTickFeed` when there are no ticks but bars exist (crypto). Confirm `BacktestCommand` passes `candleWindow` (the stream timeframe) into `fromSource`; if it currently passes `null`, resolve the `TimeWindow` from the strategy's stream timeframe and pass it (the bar synthesis needs it).

- [ ] **Step 4: Run to verify pass** — `./gradlew --offline test --tests "com.qkt.backtest.BarBacktestTest"` PASS; run `./gradlew --offline test --tests "com.qkt.backtest.*"` to confirm existing tick-backtests are unaffected. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Fix the docs.** In `docs/how-to/fetch-data.md`, correct the claim that backtests read fetched bars "automatically" — describe the now-true flow: `qkt fetch BROKER:SYMBOL --tf <tf>` then `qkt backtest` replays those bars (synthesized to O→L→H→C ticks; bars-only venues like Bybit now backtest). Note the pessimistic intra-bar fidelity caveat.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/Backtest.kt docs/how-to/fetch-data.md src/test/kotlin/com/qkt/backtest/BarBacktestTest.kt
git commit -m "feat(backtest): replay fetched bars when no ticks are available (#214)"
```

---

## Final verification

- [ ] `./gradlew --offline test --tests "com.qkt.backtest.*" --tests "com.qkt.marketdata.*" --tests "com.qkt.app.IndicatorWarmer*"` — green (no regression in existing tick-backtests; the e2e bar-backtest produces trades).
- [ ] `./gradlew --offline ktlintCheck` — clean.
- [ ] PR to `dev` (`Refs #214`). CI runs the full suite.

## Notes for the implementer

- **Read first:** `candles/CandleAggregator.kt` + `candles/TimeWindow.kt` (the emit API + `TimeWindow` construction, for Task 1's re-aggregation assertion), an existing `Backtest.fromSource` test (Task 4 harness), and an existing `IndicatorWarmer` test (Task 2 harness).
- **DRY:** `candleToTicks` is the single definition of the O→L→H→C convention — Task 2's warmup and Task 1's `BarTickFeed` both call it; do not duplicate it.
- **Don't touch the engine or live path.** The whole point is that bar-synthesized ticks flow through the *same* pipeline; if you find yourself adding a candle input to the engine, stop — that breaks backtest=live.
- **Determinism:** synthesis is a pure function of bars + the fixed convention; no clock/random/ordering surprises.
