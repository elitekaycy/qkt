# Phase 32 — bid/ask DSL exposure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a DSL strategy read `gold.bid` / `gold.ask` / `gold.spread`, so it can gate on the bid/ask spread.

**Architecture:** bid/ask already arrive on every `Tick` and are populated by `Mt5TickFeedSource`. The DSL evaluates expressions against a closed `Candle`, so the candle must carry bid/ask: the aggregator snapshots them from the window's last tick (the same tick that sets `close`), and `ExprCompiler` resolves the new field names off the candle. The evaluator already propagates `Value.Undefined` through every operator, so a null spread needs no new null-handling — it resolves to `Value.Undefined` and a `WHEN` referencing it simply does not fire.

**Tech Stack:** Kotlin, JUnit 5, AssertJ. Build: `./gradlew`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-21-phase32-bid-ask-dsl-design.md`

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/kotlin/com/qkt/marketdata/Candle.kt` | OHLC candle value type | Add `bid`/`ask` fields + derived `mid`/`spread` |
| `src/main/kotlin/com/qkt/candles/CandleAggregator.kt` | Aggregate ticks into candles | `MutableCandle` carries last tick's bid/ask |
| `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` | Compile DSL expressions | `compileStreamField` resolves `bid`/`ask`/`spread` |
| `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt` | Kotlin-DSL stream accessor | Add `bid`/`ask`/`spread` properties |
| `docs/reference/dsl/streams.md` | DSL stream-field reference | Document the three new fields |
| `docs/phases/phase-32-bid-ask.md` | Phase changelog | New, written at merge |

**No change needed:** the text parser (`Parser.kt` `expectFieldName` already accepts any identifier after `.`, producing `StreamFieldRef(alias, field)`); `IndicatorBinding.kt` (indicator series over spread is out of scope — a `SMA(gold.spread,…)` keeps failing its existing field-set `require`, which is the intended behaviour).

---

## Task 1: Candle carries bid/ask with derived mid/spread

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/Candle.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/CandleTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/marketdata/CandleTest.kt`:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleTest {
    private fun candle(
        bid: BigDecimal? = null,
        ask: BigDecimal? = null,
    ) = Candle(
        symbol = "XAUUSD",
        open = Money.of("2400"),
        high = Money.of("2402"),
        low = Money.of("2399"),
        close = Money.of("2401"),
        volume = Money.of("10"),
        startTime = 0L,
        endTime = 60_000L,
        bid = bid,
        ask = ask,
    )

    @Test
    fun `mid and spread are null when bid or ask absent`() {
        assertThat(candle().mid).isNull()
        assertThat(candle().spread).isNull()
        assertThat(candle(bid = Money.of("2400.5")).spread).isNull()
    }

    @Test
    fun `mid and spread compute when both bid and ask present`() {
        val c = candle(bid = Money.of("2400.5"), ask = Money.of("2401.5"))
        assertThat(c.mid).isEqualByComparingTo(Money.of("2401.0"))
        assertThat(c.spread).isEqualByComparingTo(Money.of("1.0"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.marketdata.CandleTest'`
Expected: FAIL — compilation error, `no value passed for parameter 'bid'` / unresolved `mid`/`spread`. The fields do not exist yet.

- [ ] **Step 3: Write the implementation**

Replace `src/main/kotlin/com/qkt/marketdata/Candle.kt` with:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * OHLC candle aggregated over a closed time window.
 *
 * Produced by the candle aggregator on its `EVERY` boundary and published as
 * [com.qkt.events.CandleEvent]. `endTime` is exclusive — the window covers
 * `[startTime, endTime)`. `bid`/`ask` are the quote from the last tick in the
 * window; they are `null` when the feed carries no bid/ask. `mid` and `spread`
 * are derived, computed on access, and `null` when either side is absent.
 */
data class Candle(
    val symbol: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val startTime: Long,
    val endTime: Long,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
) {
    /** Midpoint of bid+ask, or `null` if either side isn't populated. */
    val mid: BigDecimal?
        get() =
            if (bid != null && ask != null) {
                bid
                    .add(ask, Money.CONTEXT)
                    .divide(BigDecimal(2), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            } else {
                null
            }

    /** ask − bid, or `null` if either side isn't populated. */
    val spread: BigDecimal?
        get() =
            if (bid != null && ask != null) {
                ask.subtract(bid, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            } else {
                null
            }
}
```

`bid`/`ask` default to `null`, so the ~12 existing positional `Candle(...)` call sites stay valid — this is an additive, non-breaking change.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.marketdata.CandleTest'`
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/Candle.kt src/test/kotlin/com/qkt/marketdata/CandleTest.kt
git commit -m "feat(marketdata): add bid/ask to Candle with derived mid/spread"
```

---

## Task 2: Candle aggregator carries bid/ask from the last tick

**Files:**
- Modify: `src/main/kotlin/com/qkt/candles/CandleAggregator.kt`
- Test: `src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `CandleAggregatorTest` (inside the class, before the closing brace):

```kotlin
    @Test
    fun `closed candle carries bid and ask from the last tick in the window`() {
        aggregator()
        bus.publish(
            TickEvent(Tick("XAUUSD", Money.of("2400.0"), 0L, bid = Money.of("2399.8"), ask = Money.of("2400.2"))),
        )
        bus.publish(
            TickEvent(Tick("XAUUSD", Money.of("2401.0"), 30_000L, bid = Money.of("2400.7"), ask = Money.of("2401.3"))),
        )
        // tick past endTime closes the window
        bus.publish(TickEvent(Tick("XAUUSD", Money.of("2402.0"), 75_000L)))

        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.bid).isEqualByComparingTo(Money.of("2400.7"))
        assertThat(captured[0].candle.ask).isEqualByComparingTo(Money.of("2401.3"))
    }

    @Test
    fun `closed candle has null bid and ask when ticks carry none`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)

        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.bid).isNull()
        assertThat(captured[0].candle.ask).isNull()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.candles.CandleAggregatorTest'`
Expected: FAIL — `closed candle carries bid and ask...` fails: `captured[0].candle.bid` is `null` because the aggregator never sets it. (The second test passes already — that's fine; it guards the null path once the first is implemented.)

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/com/qkt/candles/CandleAggregator.kt`:

Add `bid`/`ask` to the `MutableCandle` constructor and have `update` / `toCandle` carry them — replace the `MutableCandle` class with:

```kotlin
    private class MutableCandle(
        val symbol: String,
        val open: BigDecimal,
        var high: BigDecimal,
        var low: BigDecimal,
        var close: BigDecimal,
        var volume: BigDecimal,
        val startTime: Long,
        val endTime: Long,
        var bid: BigDecimal?,
        var ask: BigDecimal?,
    ) {
        fun update(tick: Tick) {
            if (tick.price > high) high = tick.price
            if (tick.price < low) low = tick.price
            close = tick.price
            if (tick.volume != null) volume = volume.add(tick.volume)
            bid = tick.bid
            ask = tick.ask
        }

        fun toCandle(): Candle =
            Candle(symbol, open, high, low, close, volume, startTime, endTime, bid, ask)
    }
```

And in `newState`, pass the first tick's bid/ask — replace the `MutableCandle(...)` construction with:

```kotlin
        return MutableCandle(
            symbol = tick.symbol,
            open = tick.price,
            high = tick.price,
            low = tick.price,
            close = tick.price,
            volume = tick.volume ?: Money.ZERO,
            startTime = start,
            endTime = end,
            bid = tick.bid,
            ask = tick.ask,
        )
```

`bid`/`ask` follow `close` — last tick in the window wins.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.candles.CandleAggregatorTest'`
Expected: PASS — all tests in the class green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/candles/CandleAggregator.kt src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt
git commit -m "feat(marketdata): carry bid/ask through candle aggregation"
```

---

## Task 3: ExprCompiler resolves gold.bid / gold.ask / gold.spread

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `ExprCompilerStreamFieldTest` (inside the class). The class already defines `candle`, `ctx`, `EvalContext`, `HubKey`, and imports `StreamFieldRef`, `Value`:

```kotlin
    @Test
    fun `bid ask spread map to candle bid ask spread`() {
        val quoteCtx =
            EvalContext(
                candle = candle.copy(bid = BigDecimal("104"), ask = BigDecimal("106")),
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val ec = ExprCompiler()
        assertThat((ec.compile(StreamFieldRef("btc", "bid")).evaluate(quoteCtx) as Value.Num).v)
            .isEqualByComparingTo("104")
        assertThat((ec.compile(StreamFieldRef("btc", "ask")).evaluate(quoteCtx) as Value.Num).v)
            .isEqualByComparingTo("106")
        assertThat((ec.compile(StreamFieldRef("btc", "spread")).evaluate(quoteCtx) as Value.Num).v)
            .isEqualByComparingTo("2")
    }

    @Test
    fun `bid ask spread are Undefined when the candle has no quote`() {
        val ec = ExprCompiler()
        assertThat(ec.compile(StreamFieldRef("btc", "bid")).evaluate(ctx)).isEqualTo(Value.Undefined)
        assertThat(ec.compile(StreamFieldRef("btc", "ask")).evaluate(ctx)).isEqualTo(Value.Undefined)
        assertThat(ec.compile(StreamFieldRef("btc", "spread")).evaluate(ctx)).isEqualTo(Value.Undefined)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest'`
Expected: FAIL — `compile(StreamFieldRef("btc", "bid"))` throws `IllegalArgumentException` ("Unknown stream field"), because `compileStreamField`'s `require` set does not include `bid`/`ask`/`spread`.

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`, replace the `compileStreamField` function with:

```kotlin
    private fun compileStreamField(ref: StreamFieldRef): CompiledExpr {
        require(
            ref.field in
                setOf("close", "open", "high", "low", "volume", "price", "bid", "ask", "spread"),
        ) {
            "Unknown stream field for ${ref.stream}: ${ref.field}"
        }
        return CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val candle =
                if (ctx.currentAlias == ref.stream ||
                    (ctx.currentAlias == null && ctx.candle.symbol == key.qktSymbol)
                ) {
                    ctx.candle
                } else {
                    ctx.hub.latest(key)
                }
            if (candle == null) {
                Value.Undefined
            } else {
                val fieldValue: BigDecimal? =
                    when (ref.field) {
                        "close", "price" -> candle.close
                        "open" -> candle.open
                        "high" -> candle.high
                        "low" -> candle.low
                        "volume" -> candle.volume
                        "bid" -> candle.bid
                        "ask" -> candle.ask
                        "spread" -> candle.spread
                        else -> error("unreachable")
                    }
                if (fieldValue == null) Value.Undefined else Value.Num(fieldValue)
            }
        }
    }
```

If `ExprCompiler.kt` does not already import `java.math.BigDecimal`, add the import (it is used elsewhere in the file for `Money.CONTEXT` arithmetic; verify and add if missing).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest'`
Expected: PASS — all tests in the class green, including the existing `unknown field is rejected at compile time` (`wat` is still rejected).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt
git commit -m "feat(dsl): resolve gold.bid/ask/spread stream fields"
```

---

## Task 4: A spread comparison fires only when the quote is known

This task proves the gate semantics end to end: `gold.spread < literal` is a real `Bool` when the candle has a quote, and `Value.Undefined` (non-firing) when it does not. No production code — it locks in the behaviour the spec promises.

**Files:**
- Test: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt`

- [ ] **Step 1: Write the test**

Append to `ExprCompilerStreamFieldTest`. `CmpOp`, `Cmp`, `NumLit` are DSL AST types in `com.qkt.dsl.ast` — add the imports if the test file lacks them:

```kotlin
    @Test
    fun `spread comparison is Bool when quoted and Undefined when not`() {
        val ec = ExprCompiler()
        val gate = CmpOp(StreamFieldRef("btc", "spread"), NumLit(BigDecimal("5")), Cmp.LT)

        val quotedCtx =
            EvalContext(
                candle = candle.copy(bid = BigDecimal("104"), ask = BigDecimal("106")),
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        assertThat(ec.compile(gate).evaluate(quotedCtx)).isEqualTo(Value.Bool(true))
        assertThat(ec.compile(gate).evaluate(ctx)).isEqualTo(Value.Undefined)
    }
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest'`
Expected: PASS. (If it errors on `CmpOp`/`Cmp`/`NumLit`/`Value.Bool` names, correct them against `com.qkt.dsl.ast` — `compileCmp` in `ExprCompiler.kt` shows the exact `CmpOp`/`Cmp` shape; `Value.Bool` is produced by `compileCmp`.) This test passes immediately because Task 3 already made `spread` resolvable and the evaluator already propagates `Value.Undefined` through `compileCmp` — it is a guard for that composite behaviour, not a red-green cycle.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt
git commit -m "test(dsl): cover spread-gate firing semantics"
```

---

## Task 5: StreamRef Kotlin-DSL accessors

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt`
- Test: `src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt`:

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamRefTest {
    @Test
    fun `bid ask spread produce a StreamFieldRef for the alias`() {
        val s = StreamRef("gold")
        assertThat(s.bid).isEqualTo(StreamFieldRef("gold", "bid"))
        assertThat(s.ask).isEqualTo(StreamFieldRef("gold", "ask"))
        assertThat(s.spread).isEqualTo(StreamFieldRef("gold", "spread"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StreamRefTest'`
Expected: FAIL — compilation error, unresolved `s.bid` / `s.ask` / `s.spread`.

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt`, add three properties after `val candle`:

```kotlin
    val bid: ExprAst = StreamFieldRef(alias, "bid")
    val ask: ExprAst = StreamFieldRef(alias, "ask")
    val spread: ExprAst = StreamFieldRef(alias, "spread")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StreamRefTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt
git commit -m "feat(dsl): add bid/ask/spread accessors to StreamRef"
```

---

## Task 6: Document the new stream fields

**Files:**
- Modify: `docs/reference/dsl/streams.md`

- [ ] **Step 1: Update the reference**

Open `docs/reference/dsl/streams.md`, find the section/table that lists the stream fields (`close`, `open`, `high`, `low`, `volume`, `price`). Add `bid`, `ask`, `spread` in the same format the file already uses, with this content:

- `<stream>.bid` — best bid from the last tick in the candle window. `null` (the rule does not fire) when the feed carries no quote.
- `<stream>.ask` — best ask from the last tick in the candle window. `null` when the feed carries no quote.
- `<stream>.spread` — `ask − bid`. `null` when either side is absent.

Add a short note: bid/ask/spread are populated on live feeds that carry quotes (MT5); on backtest/historical feeds they are unavailable, and a condition referencing them evaluates to "not fired". They reflect the last tick before the candle closed — freshest-observed, not the live quote at order-placement instant (see the Phase 32 spec's Accuracy bound).

- [ ] **Step 2: Verify the docs build**

Run: `./gradlew test --tests 'com.qkt.marketdata.CandleTest' --tests 'com.qkt.candles.CandleAggregatorTest' --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest' --tests 'com.qkt.dsl.kotlin.StreamRefTest'`
Expected: PASS — full Phase 32 test surface green.

- [ ] **Step 3: Run ktlint and commit**

```bash
./gradlew ktlintFormat
git add docs/reference/dsl/streams.md
git commit -m "docs: document bid/ask/spread stream fields"
```

(If `ktlintFormat` changed any Kotlin file, amend it into the relevant task commit before this point is reached, or stage and commit it as `style(dsl): ktlint format`.)

---

## Task 7: Phase changelog and PR

**Files:**
- Create: `docs/phases/phase-32-bid-ask.md`

- [ ] **Step 1: Write the phase changelog**

Create `docs/phases/phase-32-bid-ask.md` following the qkt skill's phase-changelog requirements (Summary, What's new, Migration, Usage cookbook, Testing patterns, Known limitations, References). What's new: `Candle.bid/ask/mid/spread`; `gold.bid`/`.ask`/`.spread` DSL fields; `StreamRef.bid/ask/spread`. Usage cookbook: a `WHEN gold.spread < 5.0` gate example. Known limitations: live-feed-only (null in backtest); freshest-observed not placement-instant (quote the spec's Accuracy bound); indicator series over spread not supported.

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-32-bid-ask.md
git commit -m "docs: phase 32 changelog for bid/ask dsl exposure"
```

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin phase32-bid-ask-dsl
```

Open the PR with title `[phase 32] feat(dsl): expose bid/ask/spread to strategies` and the qkt PR template (Phase / Summary / Changes / Tests / Backwards compatibility / Out of scope / Risk). Out of scope: backtest bid/ask data (Phase 33); the hedge-straddle spread-gate edit (qkt-prod follow-up); indicator series over spread.

---

## Self-Review

**Spec coverage:** Candle model → Task 1. Candle aggregator → Task 2. DSL surface (`ExprCompiler`) → Task 3. Null semantics → Task 3 + Task 4 (free via `Value.Undefined`). `StreamRef` → Task 5. Testing (Candle, aggregator, DSL resolve, null, end-to-end gate) → Tasks 1–5. Docs → Task 6. Parser — spec says the parser must accept the fields; confirmed it already does (no task needed, noted in File Structure). All spec sections covered.

**Placeholders:** none — every code step has complete code; the docs task names the exact file and content.

**Type consistency:** `Candle(... bid, ask)` defined in Task 1 and used identically in Task 2's `toCandle`; `StreamFieldRef(alias, field)` used consistently in Tasks 3–5; `Value.Undefined` / `Value.Num` / `Value.Bool` match `ExprCompiler`'s evaluator.
