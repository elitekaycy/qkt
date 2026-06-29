# Tick-resolved fills — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `--bars --tick-fills` backtest mode that is byte-identical to a full-tick replay but decodes ticks only for bars where a fill is possible.

**Architecture:** Keep the replay engine, `OrderManager`, and broker untouched. Drive the existing `advanceUntil`/`ingest` loop with a *smart feed* that, per bar, yields either the real tick slice (when a live order/position level is reachable in that bar) or the cheap synthetic `O→L→H→C` ticks (when no fill is possible — the synthesized candle is identical to the prebuilt bar, so engine state at bar close matches full-tick). The feed asks a predicate backed by `OrderManager`, queried at each bar boundary (so it sees orders placed at the previous bar's close).

**Tech Stack:** Kotlin, JUnit 5 + AssertJ, Gradle. Binary mmap'd tick store (`BinaryTickFeed`), prebuilt bar store (`BinaryBarStore`), DSL-compiled strategies.

## Global Constraints

- **Correctness = byte-identical to the full-tick replay.** Same trades, P&L, equity curve, drawdown, rejections. Not "close" — identical. This is the acceptance gate (Task 5).
- **Backtest/feed layer only.** `OrderManager`'s fill path (`evaluateTriggers`/`triggerHit`/`trailLevel`), `RiskEngine`, broker fill/slippage models, and the live path are unchanged. The only `OrderManager` addition is a **read-only** query (Task 2) — it does not alter fill behavior.
- **No mocks** (qkt §11): real types + anonymous objects. JUnit 5 + AssertJ. Tests deterministic (`FixedClock`, seeded).
- **KDoc on every new public type/method** (qkt §10). No emojis, no AI attribution. Conventional commits, subject-only (qkt §3). Files aim < 200 lines (qkt §12).
- **Branch:** `feat-tick-resolved-fills` off `dev` (separate concern from `feat-bars-fill-tf`).

---

## File Structure

- `src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt` — **modify**: add `slice(fromMs, toMs)` (random-access window via binary search on the sorted timestamp column) + `startIndex`/`endIndex` bounds.
- `src/main/kotlin/com/qkt/app/OrderManager.kt` — **modify**: add read-only `canTriggerInBar(symbol, low, high): Boolean`.
- `src/main/kotlin/com/qkt/research/BarResolvedFeed.kt` — **create**: the smart `TickFeed` (bar stream + per-symbol slice provider + fill-possible predicate → real-or-synthetic ticks per bar).
- `src/main/kotlin/com/qkt/cli/BacktestContext.kt` — **modify**: parse `--tick-fills`, build `BarResolvedFeed` and bind its predicate to the engine's `OrderManager`.
- `src/test/kotlin/com/qkt/marketdata/BinaryTickFeedSliceTest.kt` — **create**.
- `src/test/kotlin/com/qkt/app/OrderManagerTriggerRangeTest.kt` — **create**.
- `src/test/kotlin/com/qkt/research/BarResolvedFeedTest.kt` — **create**.
- `src/test/kotlin/com/qkt/backtest/TickResolvedParityTest.kt` — **create** (the oracle).

---

### Task 1: Random-access tick slice on `BinaryTickFeed`

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryTickFeedSliceTest.kt`

**Interfaces:**
- Consumes: existing `BinaryTickFeed(path)`, `header.tickCount`, `tsBase`, `buf`, `index`; `BinaryTickWriter().write(path, symbol, ticks)`.
- Produces: `fun slice(fromMs: Long, toMs: Long): BinaryTickFeed` — repositions the feed to `[fromMs, toMs)` (half-open). After calling, `next()` yields exactly the ticks with `fromMs <= ts < toMs`, in order, then `null`. Re-callable for forward windows.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickFeedSliceTest {
    private fun write(dir: Path): Path {
        val f = dir.resolve("d.bin")
        val ticks = (0 until 10).map { Tick("X", Money.of((100 + it).toString()), it * 1000L) } // ts 0,1000,...,9000
        BinaryTickWriter().write(f, "X", ticks)
        return f
    }

    private fun drain(feed: BinaryTickFeed): List<Long> {
        val out = mutableListOf<Long>()
        while (true) { val t = feed.next() ?: break; out.add(t.timestamp) }
        return out
    }

    @Test
    fun `slice yields only the half-open window`(@TempDir dir: Path) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(3000L, 7000L)
        assertThat(drain(feed)).containsExactly(3000L, 4000L, 5000L, 6000L) // 7000 excluded
    }

    @Test
    fun `slice with no ticks in window yields nothing`(@TempDir dir: Path) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(9500L, 9800L)
        assertThat(drain(feed)).isEmpty()
    }

    @Test
    fun `successive forward slices each yield their window`(@TempDir dir: Path) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(0L, 2000L); assertThat(drain(feed)).containsExactly(0L, 1000L)
        feed.slice(2000L, 4000L); assertThat(drain(feed)).containsExactly(2000L, 3000L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.marketdata.BinaryTickFeedSliceTest"`
Expected: FAIL — `slice` unresolved reference.

- [ ] **Step 3: Implement the slice**

In `BinaryTickFeed.kt`, add an `endIndex` bound and the slice. Replace the bound check in `next()` from `if (index >= header.tickCount)` to `if (index >= endIndex)`, and initialize `private var endIndex: Int = header.tickCount`.

```kotlin
/**
 * Reposition this feed to the half-open time window `[fromMs, toMs)`. After calling, `next()`
 * yields exactly the ticks whose timestamp is in that range, in order, then null. Backed by a
 * binary search over the sorted timestamp column (O(log n)) — no scan of skipped ticks. Re-callable
 * for forward windows. e.g. ticks at 0..9000 step 1000, slice(3000, 7000) -> 3000,4000,5000,6000.
 */
fun slice(fromMs: Long, toMs: Long): BinaryTickFeed {
    val b = buf ?: return this
    index = lowerBound(b, fromMs)
    endIndex = lowerBound(b, toMs)
    return this
}

private fun lowerBound(b: java.nio.ByteBuffer, target: Long): Int {
    var lo = 0
    var hi = header.tickCount
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (b.getLong(tsBase + mid * Long.SIZE_BYTES) < target) lo = mid + 1 else hi = mid
    }
    return lo
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.BinaryTickFeedSliceTest"`
Expected: PASS (3 tests). Also run `--tests "com.qkt.marketdata.BinaryTickParityTest"` to confirm the `endIndex` change didn't break the full-drain path.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt src/test/kotlin/com/qkt/marketdata/BinaryTickFeedSliceTest.kt
git commit -m "feat(marketdata): add time-range slice to BinaryTickFeed"
```

---

### Task 2: `OrderManager.canTriggerInBar` — the fill-possible predicate

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Test: `src/test/kotlin/com/qkt/app/OrderManagerTriggerRangeTest.kt`

**Interfaces:**
- Consumes: existing private `liveBySymbol`, `orders`, `trailLevel(managed)`, `OrderRequest.*`, `OrderState.PENDING`.
- Produces: `fun canTriggerInBar(symbol: String, low: BigDecimal, high: BigDecimal): Boolean` — true iff a live PENDING order on `symbol` could trigger within `[low, high]`, OR a trailing stop is live on `symbol` (path-dependent → always resolve on ticks). Direction-aware so a gap-open through a level still returns true.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.app

import com.qkt.common.Money
// ... (use the same OrderManager test harness as OrderManagerTest.kt: real EventBus, FixedClock, IdGenerator)
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTriggerRangeTest {
    // Build an OrderManager via the same construction OrderManagerTest uses, submit a resting order,
    // then assert canTriggerInBar.
    @Test
    fun `buy stop is reachable when bar high crosses it`() {
        val om = newOrderManager()
        om.submit(OrderRequest.Stop(symbol = "X", side = Side.BUY, quantity = Money.ONE, stopPrice = Money.of("100")))
        assertThat(om.canTriggerInBar("X", Money.of("98"), Money.of("101"))).isTrue   // high 101 >= 100
        assertThat(om.canTriggerInBar("X", Money.of("96"), Money.of("99"))).isFalse   // never reaches 100
    }

    @Test
    fun `buy stop is reachable on a gap-open above it`() {
        val om = newOrderManager()
        om.submit(OrderRequest.Stop(symbol = "X", side = Side.BUY, quantity = Money.ONE, stopPrice = Money.of("100")))
        // bar gaps to [102,103] — level 100 is below low, but a buy-stop fires (high 103 >= 100)
        assertThat(om.canTriggerInBar("X", Money.of("102"), Money.of("103"))).isTrue
    }

    @Test
    fun `no live order means not reachable`() {
        assertThat(newOrderManager().canTriggerInBar("X", Money.of("0"), Money.of("9999"))).isFalse
    }
}
```

(`newOrderManager()` is a local helper mirroring `OrderManagerTest.kt`'s setup — real `EventBus`, `FixedClock`, `IdGenerator`, `MarketPriceTracker`. Copy that harness; do not mock.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTriggerRangeTest"`
Expected: FAIL — `canTriggerInBar` unresolved reference.

- [ ] **Step 3: Implement the read-only query**

In `OrderManager.kt`:

```kotlin
/**
 * True iff a live order on [symbol] could trigger within the bar range [low, high]. Direction-aware,
 * so a gap-open through a level still counts (a buy stop at 100 fires on a bar that opens at 102).
 * A live trailing stop always returns true — its level depends on the intrabar path, so the bar
 * extremes alone cannot rule a fill out. Read-only: does not mutate order state or fire triggers.
 */
fun canTriggerInBar(symbol: String, low: BigDecimal, high: BigDecimal): Boolean {
    val ids = liveBySymbol[symbol] ?: return false
    for (id in ids) {
        val m = orders[id] ?: continue
        if (m.state != OrderState.PENDING) continue
        when (val r = m.request) {
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit, is OrderRequest.ArmedTrailingStop -> return true
            is OrderRequest.Stop ->
                if (if (r.side == Side.BUY) high >= r.stopPrice else low <= r.stopPrice) return true
            is OrderRequest.StopLimit ->
                if (if (r.side == Side.BUY) high >= r.stopPrice else low <= r.stopPrice) return true
            is OrderRequest.Limit ->
                if (if (r.side == Side.BUY) low <= r.limitPrice else high >= r.limitPrice) return true
            is OrderRequest.IfTouched ->
                if (if (r.side == Side.BUY) low <= r.triggerPrice else high >= r.triggerPrice) return true
            else -> {}
        }
    }
    return false
}
```

(Match the exact `OrderRequest` subtype names and side semantics already used in `triggerHit` at `OrderManager.kt:1746`. If a subtype's reach direction differs there, mirror `triggerHit` exactly — `triggerHit` is the source of truth for direction.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTriggerRangeTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerTriggerRangeTest.kt
git commit -m "feat(app): add read-only canTriggerInBar query to OrderManager"
```

---

### Task 3: `BarResolvedFeed` — real-or-synthetic ticks per bar

**Files:**
- Create: `src/main/kotlin/com/qkt/research/BarResolvedFeed.kt`
- Test: `src/test/kotlin/com/qkt/research/BarResolvedFeedTest.kt`

**Interfaces:**
- Consumes: `TickFeed` interface (`next(): Tick?`), `Candle`, `candleToTicks(candle)` (from `com.qkt.marketdata.source`).
- Produces:
```kotlin
class BarResolvedFeed(
    bars: Sequence<Candle>,                                   // time-ordered, merged across symbols
    private val sliceProvider: (symbol: String, fromMs: Long, toMs: Long) -> List<Tick>,
    private val fillPossible: (symbol: String, low: BigDecimal, high: BigDecimal) -> Boolean,
) : TickFeed
```
  When a bar is fill-possible, `next()` streams `sliceProvider(sym, bar.startTime, bar.endTime)` (the real ticks, exact fills + exact candle). Otherwise it streams `candleToTicks(bar)` (synthetic `O→L→H→C`, cheap; the candle it builds is identical to the prebuilt bar). `fillPossible` is queried once per bar, at the moment that bar's first tick is needed — so it reflects orders placed at the previous bar's close.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarResolvedFeedTest {
    private fun bar(start: Long, o: String, h: String, l: String, c: String) =
        Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), startTime = start, endTime = start + 1000)

    private fun drain(f: BarResolvedFeed): List<String> {
        val out = mutableListOf<String>(); while (true) { val t = f.next() ?: break; out.add(t.price.toPlainString()) }; return out
    }

    @Test
    fun `fill-possible bar streams the real slice`() {
        val realTicks = listOf(Tick("X", Money.of("100"), 0), Tick("X", Money.of("101"), 500))
        val f = BarResolvedFeed(
            bars = sequenceOf(bar(0, "100", "102", "99", "101")),
            sliceProvider = { _, _, _ -> realTicks },
            fillPossible = { _, _, _ -> true },
        )
        assertThat(drain(f)).containsExactly("100", "101")   // the real ticks, not O-L-H-C
    }

    @Test
    fun `fill-impossible bar streams synthetic O-L-H-C`() {
        val f = BarResolvedFeed(
            bars = sequenceOf(bar(0, "100", "102", "99", "101")),
            sliceProvider = { _, _, _ -> error("must not slice an impossible bar") },
            fillPossible = { _, _, _ -> false },
        )
        assertThat(drain(f)).containsExactly("100", "99", "102", "101")   // O, L, H, C
    }

    @Test
    fun `predicate is queried per bar with that bar's range`() {
        val seen = mutableListOf<Pair<String, String>>()
        val f = BarResolvedFeed(
            bars = sequenceOf(bar(0, "100", "102", "99", "101"), bar(1000, "101", "105", "100", "104")),
            sliceProvider = { _, _, _ -> listOf(Tick("X", Money.of("1"), 0)) },
            fillPossible = { _, low, high -> seen.add(low.toPlainString() to high.toPlainString()); false },
        )
        drain(f)
        assertThat(seen).containsExactly("99" to "102", "100" to "105")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.BarResolvedFeedTest"`
Expected: FAIL — `BarResolvedFeed` unresolved reference.

- [ ] **Step 3: Implement the feed**

```kotlin
package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.candleToTicks
import java.math.BigDecimal

/**
 * A [TickFeed] that drives a `--bars` replay but resolves fills on real ticks where one is possible.
 * For each time-ordered bar it asks [fillPossible]; if true it streams the bar's real tick slice
 * (via [sliceProvider]) so SL/TP/entry fills resolve exactly; if false it streams the synthetic
 * `O->L->H->C` ticks (cheap — and since no fill was possible, indistinguishable from the real path
 * for the outcome, while the candle it builds equals the prebuilt bar). [fillPossible] is queried
 * once per bar, when that bar's first tick is pulled, so it reflects orders placed at the prior
 * bar's close. e.g. flat bar -> 4 synthetic ticks; bar with a stop in range -> the real ~hundreds.
 */
class BarResolvedFeed(
    bars: Sequence<Candle>,
    private val sliceProvider: (String, Long, Long) -> List<Tick>,
    private val fillPossible: (String, BigDecimal, BigDecimal) -> Boolean,
) : TickFeed {
    private val barIter = bars.iterator()
    private var buffer: Iterator<Tick> = emptyList<Tick>().iterator()

    override fun next(): Tick? {
        while (!buffer.hasNext()) {
            if (!barIter.hasNext()) return null
            val bar = barIter.next()
            buffer =
                if (fillPossible(bar.symbol, bar.low, bar.high)) {
                    sliceProvider(bar.symbol, bar.startTime, bar.endTime).iterator()
                } else {
                    candleToTicks(bar).iterator()
                }
        }
        return buffer.next()
    }
}
```

(`TickFeed` lives in `com.qkt.marketdata`; import it. Confirm `Candle` field names `symbol`/`low`/`high`/`startTime`/`endTime` against `Candle.kt`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.BarResolvedFeedTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/research/BarResolvedFeed.kt src/test/kotlin/com/qkt/research/BarResolvedFeedTest.kt
git commit -m "feat(research): add BarResolvedFeed for tick-resolved bar replay"
```

---

### Task 4: Wire `--tick-fills` into the backtest

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestContext.kt` (the `forceBars` block, ~lines 333–396) and the engine/feed construction in `Backtest.kt` (`replayFeed`, ~272–303).
- Test: covered by the parity test in Task 5 (this task is integration wiring; its deliverable is exercised there).

**Interfaces:**
- Consumes: `args.flag("tick-fills")`, `BinaryTickFeed(path).slice(...)` (Task 1), `OrderManager.canTriggerInBar` (Task 2), `BarResolvedFeed` (Task 3), the per-symbol bar windows already resolved for `--bars`, and the per-symbol tick-store paths used by the full-tick feed.
- Produces: when `--bars --tick-fills` is set, the replay runs on a `BarResolvedFeed` whose `fillPossible` calls the engine's `OrderManager.canTriggerInBar` and whose `sliceProvider` draws from a per-symbol `BinaryTickFeed.slice(...)`. Requires both a built bar store (signals + predicate) and the tick store (fills); a missing tick slice for a fill-possible bar errors via the existing `SetupError`/incomplete-data path (do not approximate).

- [ ] **Step 1: Add the flag and guard**

In `BacktestContext.build()`, alongside `forceBars`:

```kotlin
val tickFills = args.flag("tick-fills")
require(!tickFills || forceBars) { "--tick-fills requires --bars (bars drive signals; ticks resolve fills)" }
```

When `tickFills`, the replay must use a `BarResolvedFeed` instead of the plain `BarTickFeed`. Build, per symbol: a `BinaryTickFeed` over the tick store (for `sliceProvider`) and the bar `Sequence<Candle>` (for the bar stream — the same source `--bars` uses). Bind `fillPossible = { sym, lo, hi -> engine.orderManager.canTriggerInBar(sym, lo, hi) }` after the engine is constructed (late-bound, since the feed and the `OrderManager` are created together — pass a holder or set the predicate post-construction).

- [ ] **Step 2: Multi-symbol bar merge**

`BarResolvedFeed` consumes a single time-ordered `Sequence<Candle>`. For a multi-symbol (basket) backtest, merge each symbol's bar sequence by `startTime` (k-way merge, ties broken by symbol order to match the full-tick stream ordering). Reuse the existing multi-symbol feed-merge utility if one exists (grep `merge` under `com.qkt.research`/`marketdata`); otherwise add a small `mergeByStartTime(perSymbol: List<Sequence<Candle>>)`. The `sliceProvider` is keyed by symbol so each draws from its own tick store.

- [ ] **Step 3: Document the mode**

Update the `--bars` help/usage text and the stderr notice: under `--tick-fills` it is **exact** (not the "not for grading" research tier). Add a one-line KDoc note where the feed is selected.

- [ ] **Step 4: Manual smoke**

Run a tiny `--bars --tick-fills` backtest over a built day and confirm it produces `--json` with trades (no crash, no "not for grading" wording in this mode).

Run: `./gradlew installDist && build/install/qkt/bin/qkt backtest <fixture.qkt> --from <d> --to <d+1> --data-root <root> --bars --tick-fills --json --allow-incomplete`
Expected: valid JSON with a `"trades":` field.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BacktestContext.kt src/main/kotlin/com/qkt/backtest/Backtest.kt
git commit -m "feat(backtesting): wire --tick-fills bar-driven exact replay"
```

---

### Task 5: Parity oracle — tick-resolved == full-tick (the acceptance gate)

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/TickResolvedParityTest.kt`

**Interfaces:**
- Consumes: `Backtest` (full-tick path) and the `--tick-fills` path (Task 4); the comparison shape from `SweepReplayParityTest` (`result.global.tradeCount`, `totalPnL`, `maxDrawdown`, `result.trades`, equity curve).

- [ ] **Step 1: Write the parity test across order shapes**

Build a realistic intrabar tick series (e.g. a sine path with both up and down legs inside each bar so SL/TP both-hit and gap cases actually occur), write it to a tick store, build bars from it, then run each strategy two ways and assert byte-identity. Cover: SL/TP bracket (long and short), stop-entry, limit-entry, if-touched, trailing stop, intrabar entry+exit in one bar, gap-open through a level, and a 2-symbol basket.

```kotlin
@Test
fun `tick-resolved equals full-tick for an SL TP bracket`() {
    val ticks = sinePathTicks(days = 2)                 // both extremes hit inside bars
    seedTickStore(dataRoot, ticks); buildBars(dataRoot, "1m")
    val full = runBacktest(bracketStrategy, dataRoot, extraArgs = emptyList())          // full ticks
    val resolved = runBacktest(bracketStrategy, dataRoot, extraArgs = listOf("--bars", "--tick-fills"))
    assertThat(resolved.global.tradeCount).isEqualTo(full.global.tradeCount)
    assertThat(resolved.global.totalPnL).isEqualByComparingTo(full.global.totalPnL)
    assertThat(resolved.global.maxDrawdown).isEqualByComparingTo(full.global.maxDrawdown)
    assertThat(resolved.trades).isEqualTo(full.trades)                                   // exact trade list
    assertThat(resolved.equityCurve).isEqualTo(full.equityCurve)                         // exact curve
}
// ... one test per shape above (each its own backtick-sentence name).
```

- [ ] **Step 2: Run — expect failures that reveal real divergences**

Run: `./gradlew test --tests "com.qkt.backtest.TickResolvedParityTest"`
Expected initially: FAIL on the cases where event ordering or equity-sampling diverges. **Each failure is a real ordering bug to fix in Task 4's wiring — not a reason to weaken the assertion.**

- [ ] **Step 3: Resolve divergences**

Likely culprits, fix in order: (a) **equity-curve cadence** — confirm the equity collector samples at candle close, not per-tick; if per-tick, the synthetic (4-tick) inactive bars vs full-tick (many-tick) bars produce different sample counts → the fix is to sample bar-aligned (which full-tick also does on candle close). (b) **candle-close-vs-first-tick ordering** — ensure a fill-possible bar's real ticks drive the same candle-close moment as full-tick. (c) **OCO both-hit within one bar** — the within-slice tick order resolves the first-crossed leg; verify against full-tick.

- [ ] **Step 4: Run — all green**

Run: `./gradlew test --tests "com.qkt.backtest.TickResolvedParityTest"`
Expected: PASS (all shapes byte-identical).

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/TickResolvedParityTest.kt
git commit -m "test(backtesting): parity oracle for tick-resolved fills vs full ticks"
```

---

### Task 6: Benchmark, docs, and full build

**Files:**
- Modify: `docs/parity/backtest-vs-live.md` (note `--tick-fills` is exact, equal to full ticks).
- Modify: `docs/superpowers/specs/2026-06-29-tick-resolved-fills-design.md` (status → implemented; record measured speedup).

- [ ] **Step 1: Benchmark vs full ticks**

On the data box, run a flat-heavy strategy and an always-in strategy both ways over a 540-day window; record wall-clock and ticks-decoded for each. Log what fraction of bars needed ticks (no silent caps). Expected: flat-heavy meaningfully faster than full ticks; always-in roughly on par; both byte-identical.

- [ ] **Step 2: Update docs**

Add a `--tick-fills` row to the parity catalog stating it is exact (== full-tick), and record the measured speedup range in the spec. KDoc already added on `slice`, `canTriggerInBar`, `BarResolvedFeed`.

- [ ] **Step 3: Full build + ktlint**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, all tests pass, ktlint clean.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: mark --tick-fills exact and record speedup"
```

---

## Self-Review

**Spec coverage:** §3 core idea → Tasks 3–4. §4 fill-possible predicate → Task 2. §5.1 slices → Task 1. §5.2 minimal per-tick work → inherent (synthetic ticks on inactive bars skip nothing extra; active ticks run the full unchanged `ingest`, which is the price we accept for exactness — note: §5.2's "skip strategy.onTick on active ticks" is NOT done here, because we keep the engine unchanged for parity; the speedup comes from §5.1 skipping whole inactive bars, not from cheaper active ticks). §6 parity → Task 5. §7 boundary → Global Constraints + Task 2 is read-only. §8 CLI → Task 4. §10 perf → Task 6.

> **Design note (deviation from spec §5.2):** to guarantee byte-identity with the least risk, active-bar ticks flow through the *unchanged* `ingest` (strategy + candle + order manager), exactly as full-tick does. We do **not** strip `strategy.onTick`/candle work on active ticks (that would risk divergence). So the speed win is entirely from §5.1 (skipping inactive bars) + the within-bar nature of slices, not from cheaper active ticks. If benchmarks show active-tick overhead dominates, a follow-up spec can revisit stripping redundant work on active ticks behind its own parity proof.

**Placeholder scan:** Task 4 is integration wiring described against exact methods (`canTriggerInBar`, `slice`, `BarResolvedFeed`) — its correctness is gated by Task 5's oracle, which is the right place to verify integration. The multi-symbol merge (Task 4 Step 2) names the approach (k-way by `startTime`) rather than inlining code because it must follow the existing merge util if present — flagged to grep first.

**Type consistency:** `slice(fromMs, toMs)`, `canTriggerInBar(symbol, low, high)`, `BarResolvedFeed(bars, sliceProvider, fillPossible)` are referenced identically across Tasks 1→3→4. `Candle` fields and `OrderRequest` subtype names must be confirmed against source at execution (noted in Tasks 2–3).

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-29-tick-resolved-fills.md`. Two execution options:

1. **Subagent-Driven (recommended)** — a fresh subagent per task, two-stage review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session with checkpoints for review.

Which approach?
