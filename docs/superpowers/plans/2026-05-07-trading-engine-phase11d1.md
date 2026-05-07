# Phase 11d1 — Order Types, Brackets, OCO, Child Prices, Equity-Free Sizing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the DSL usable for real trading on every order type the engine already supports — LIMIT, STOP, STOP-LIMIT, TRAILING (BY/PCT), BRACKET (entry + take-profit + stop-loss), OCO (two arbitrary legs). Add child-price modes (`AT`, `BY`, `PCT`, `RR`) computed at fire time from the entry-price reference. Add TIF (`GTC`, `IOC`, `FOK`, `DAY`; defer `GTD`). Add equity-free sizing modes: `USD` notional, `RISK $<abs>` (with stop-distance lookup), `POSITION.<sym>` (close-at-full-size). `% OF EQUITY`, `% OF BALANCE`, `RISK <fraction>`, and the `DEFAULTS` block all need engine-side equity surface; deferred to 11d2.

**Architecture:** The action callable signature stays `(EvalContext) -> List<Signal>`. For non-Market orders we emit `Signal.Submit(OrderRequest.X(...))`. Each `OrderTypeAst` variant compiles to a builder closure `(EvalContext, side, qty) -> Pair<OrderRequest, entryPriceRef: BigDecimal>` where the second element is the price reference used by child-price `BY`/`PCT`/`RR` modes. `BracketAst` and `OcoAst` compile to wrappers that take the entry request + entry price and build child prices at fire time. Sizing modes compile to `(EvalContext, entryPrice, stopPrice?) -> BigDecimal` quantity computers. `RISK $<abs>` requires a resolvable stop distance — validated at compile time. The DSL gets its own per-strategy `IdGenerator` (prefix `dsl-<strategyName>-`) so emitted OrderRequests have unique IDs without the strategy needing to know about engine ID generation.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies. No new engine files — uses existing `OrderRequest.{Limit, Stop, StopLimit, TrailingStop, TrailingStopLimit, Bracket, StandaloneOCO}` and `TimeInForce` enum.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 — Phase 11d1.

**Branch:** `phase11d1-order-surface` (already cut from `main`).

---

## Design notes

### Why emit `Signal.Submit` for non-trivial orders

`Signal.Buy`/`Signal.Sell` are convenience signals that downstream `toOrderRequest()` translates into `OrderRequest.Market` with a fixed default TIF (`GTC`). They cannot carry limit/stop prices, brackets, OCO legs, or non-default TIF. Anything past plain market with default TIF must use `Signal.Submit(request)`, which carries an arbitrary `OrderRequest`.

For 11d1 we emit `Signal.Submit` for all DSL-built orders — including plain Market orders that have a non-default TIF or that come through BRACKET/OCO. Plain `BUY btc QTY 1` with no TIF still emits `Signal.Buy` (preserves the existing path; smaller diff to existing tests). `BUY btc QTY 1 TIF IOC` emits `Signal.Submit(OrderRequest.Market(... timeInForce=IOC ...))`.

### Per-strategy ID generation

`OrderRequest` requires an `id: String`. The TradingPipeline already issues IDs for `Signal.Buy`/`Signal.Sell` via its own `IdGenerator`, but `Signal.Submit` passes its `request.id` through unchanged. Strategies normally don't generate IDs.

For 11d1, each `CompiledStrategy` owns a `SequentialIdGenerator(prefix = "dsl-${ast.name}-")`. The action compiler closes over it. Every emitted `OrderRequest` gets a unique ID from this generator. IDs are unique within the strategy and prefixed so they don't collide with engine-generated IDs. The clock comes from `ctx.strategyContext.clock`.

### Child-price evaluation

`BRACKET { STOP LOSS BY 5, TAKE PROFIT RR 3 }`:
1. At fire time, determine the entry-price reference. For Market entry, this is `candle.close` (the close of the bar the rule fires on; backtest synchronous fills use this). For `LIMIT AT 100`, it's 100. For `STOP AT 95`, it's 95.
2. Compute `stopLoss = entry - 5` (long) or `entry + 5` (short) — `BY` is unsigned distance.
3. Compute `takeProfit = entry + 3 * (entry - stopLoss) = entry + 15` (long, assuming stop below entry) or analogously for short.
4. Wrap in `OrderRequest.Bracket(entry = <inner Market/Limit>, takeProfit = ..., stopLoss = ...)`.

Each child-price mode maps to a `(BigDecimal entry, BigDecimal? stopDistance) -> BigDecimal` resolver:

| Mode | Resolver |
|---|---|
| `AT <expr>` | returns the expression value (absolute price) |
| `BY <expr>` | for stop loss: `entry - sign * dist`. For take profit: `entry + sign * dist`. `sign = +1` for long, `-1` for short. |
| `PCT <expr>` | same as BY but `dist = entry * frac` |
| `RR <multiplier>` | take-profit only: `entry + sign * multiplier * stopDistance`. Errors if stopDistance is null. |

### TIF mapping

| DSL | Engine `TimeInForce` |
|---|---|
| `GTC` | `GTC` |
| `IOC` | `IOC` |
| `FOK` | `FOK` |
| `DAY` | `DAY` |
| `GTD <expr>` | **deferred** — engine enum has 4 variants only; adding GTD requires `Long` deadline field. AST node `Gtd(until)` exists; compiler errors with "deferred". |

### TRAILING PCT scaling

Engine's `OrderRequest.TrailingStop` with `TrailMode.PERCENT` expects `trailAmount` in **0–100 range** (e.g., `5` means 5%). The DSL spec uses 0–1 fractional (e.g., `0.05`). The compiler multiplies by 100 when translating.

### RISK $ sizing

`RISK $50` means "risk $50 on this trade." Quantity = $50 / abs(entry - stop). Requires a resolvable stop distance.

Resolution: walk `ActionOpts.bracket.stopLoss` (if present). If a `STOP LOSS BY <dist>`, use `dist`. If a `STOP LOSS AT <price>`, use `abs(entry - price)`. If `PCT <frac>`, use `entry * frac`. If no `BRACKET` or no stop loss in the bracket, compile-time error: "RISK $ requires a resolvable stop distance via BRACKET STOP LOSS".

(Phase 11d2 extends this to read from a strategy-level `DEFAULTS` block.)

### What stays in the AST untouched

The AST has all the order-type, child-price, sizing, and TIF nodes already (declared in 11b). 11d1 only changes the compiler. Boundary tests need refreshing.

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/dsl/compile/
├── OrderTypeCompiler.kt        # OrderTypeAst → entry-builder + entry-price closure
├── ChildPriceResolver.kt       # ChildPriceAst + side + entry + stop → absolute price
├── SizingCompiler.kt           # SizingAst → quantity computer
└── TifTranslator.kt            # TifAst → engine TimeInForce; defer Gtd

src/test/kotlin/com/qkt/dsl/compile/
├── OrderTypeCompilerTest.kt
├── ChildPriceResolverTest.kt
├── SizingCompilerTest.kt
├── TifTranslatorTest.kt
├── BracketCompileTest.kt
├── OcoCompileTest.kt
└── OrderSurfaceEndToEndTest.kt
```

### Modified files

```
src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt   # dispatch to OrderTypeCompiler/SizingCompiler
src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt      # build per-strategy IdGenerator; thread through
src/main/kotlin/com/qkt/dsl/kotlin/Actions.kt           # builders for limit/stop/etc; child price; sizing
src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt   # adapt assertions for Submit emission
```

---

## Tasks

### Task 1: `TifTranslator` (DAY/GTC/IOC/FOK; defer GTD)

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/TifTranslator.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/TifTranslatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TifTranslatorTest {
    @Test
    fun `each TIF maps to its engine enum`() {
        assertThat(TifTranslator.translate(Gtc)).isEqualTo(TimeInForce.GTC)
        assertThat(TifTranslator.translate(Ioc)).isEqualTo(TimeInForce.IOC)
        assertThat(TifTranslator.translate(Fok)).isEqualTo(TimeInForce.FOK)
        assertThat(TifTranslator.translate(Day)).isEqualTo(TimeInForce.DAY)
    }

    @Test
    fun `default TIF is GTC`() {
        assertThat(TifTranslator.translate(null)).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `GTD is deferred`() {
        assertThatThrownBy { TifTranslator.translate(Gtd(NumLit(BigDecimal.ZERO))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("deferred")
    }
}
```

- [ ] **Step 2: Implement `TifTranslator.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.TifAst
import com.qkt.execution.TimeInForce

object TifTranslator {
    fun translate(tif: TifAst?): TimeInForce =
        when (tif) {
            null, Gtc -> TimeInForce.GTC
            Ioc -> TimeInForce.IOC
            Fok -> TimeInForce.FOK
            Day -> TimeInForce.DAY
            is Gtd ->
                error(
                    "TIF GTD is deferred — engine TimeInForce enum has no GTD variant; " +
                        "revisit alongside engine deadline-bearing order surface",
                )
        }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.TifTranslatorTest'
git add src/main/kotlin/com/qkt/dsl/compile/TifTranslator.kt src/test/kotlin/com/qkt/dsl/compile/TifTranslatorTest.kt
git commit -m "feat(dsl): translate TIF AST to engine TimeInForce; defer GTD"
```

---

### Task 2: `ChildPriceResolver`

Resolves a `ChildPriceAst` + `side` + entry-price + optional stop-distance to an absolute price. Used by BRACKET and OCO when computing child prices.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/ChildPriceResolver.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ChildPriceResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChildPriceResolverTest {
    private val resolver = ChildPriceResolver(ExprCompiler())

    @Test
    fun `AT returns the absolute price`() {
        val r = resolver.compile(ChildAt(NumLit(BigDecimal("105"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("105")
    }

    @Test
    fun `BY for stop loss on long subtracts distance from entry`() {
        val r = resolver.compile(ChildBy(NumLit(BigDecimal("5"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("95")
    }

    @Test
    fun `BY for stop loss on short adds distance to entry`() {
        val r = resolver.compile(ChildBy(NumLit(BigDecimal("5"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(side = Side.SELL, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("105")
    }

    @Test
    fun `BY for take profit on long adds distance`() {
        val r = resolver.compile(ChildBy(NumLit(BigDecimal("10"))), kind = ChildKind.TAKE_PROFIT)
        assertThat(r.evaluate(side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("110")
    }

    @Test
    fun `PCT for stop loss on long subtracts entry times fraction`() {
        val r = resolver.compile(ChildPct(NumLit(BigDecimal("0.05"))), kind = ChildKind.STOP_LOSS)
        assertThat(r.evaluate(side = Side.BUY, entry = BigDecimal("100"), stopDistance = null))
            .isEqualByComparingTo("95")
    }

    @Test
    fun `RR for take profit uses stop distance`() {
        val r = resolver.compile(ChildRr(NumLit(BigDecimal("3"))), kind = ChildKind.TAKE_PROFIT)
        assertThat(r.evaluate(side = Side.BUY, entry = BigDecimal("100"), stopDistance = BigDecimal("5")))
            .isEqualByComparingTo("115")
    }

    @Test
    fun `RR without stop distance errors`() {
        val r = resolver.compile(ChildRr(NumLit(BigDecimal("3"))), kind = ChildKind.TAKE_PROFIT)
        assertThatThrownBy { r.evaluate(side = Side.BUY, entry = BigDecimal("100"), stopDistance = null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `RR for stop loss errors at compile time`() {
        assertThatThrownBy {
            resolver.compile(ChildRr(NumLit(BigDecimal("3"))), kind = ChildKind.STOP_LOSS)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Implement `ChildPriceResolver.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import java.math.BigDecimal

enum class ChildKind { STOP_LOSS, TAKE_PROFIT }

class ChildPriceResolver(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(
        child: ChildPriceAst,
        kind: ChildKind,
    ): CompiledChildPrice =
        when (child) {
            is ChildAt -> {
                val priceExpr = exprCompiler.compile(child.price)
                CompiledChildPrice { ec, _, _, _ ->
                    val v = priceExpr.evaluate(ec)
                    require(v is Value.Num) { "child AT expression must be numeric" }
                    v.v
                }
            }
            is ChildBy -> {
                val distExpr = exprCompiler.compile(child.distance)
                CompiledChildPrice { ec, side, entry, _ ->
                    val v = distExpr.evaluate(ec)
                    require(v is Value.Num) { "child BY expression must be numeric" }
                    applyDistance(side, entry, v.v, kind)
                }
            }
            is ChildPct -> {
                val fracExpr = exprCompiler.compile(child.frac)
                CompiledChildPrice { ec, side, entry, _ ->
                    val v = fracExpr.evaluate(ec)
                    require(v is Value.Num) { "child PCT expression must be numeric" }
                    val dist = entry.multiply(v.v, Money.CONTEXT)
                    applyDistance(side, entry, dist, kind)
                }
            }
            is ChildRr -> {
                require(kind == ChildKind.TAKE_PROFIT) {
                    "RR child price mode is only valid for TAKE PROFIT (got $kind)"
                }
                val multExpr = exprCompiler.compile(child.multiplier)
                CompiledChildPrice { ec, side, entry, stopDistance ->
                    val sd =
                        stopDistance
                            ?: error("RR take-profit requires a resolvable stop distance from BRACKET STOP LOSS")
                    val v = multExpr.evaluate(ec)
                    require(v is Value.Num) { "child RR expression must be numeric" }
                    applyDistance(side, entry, v.v.multiply(sd, Money.CONTEXT), ChildKind.TAKE_PROFIT)
                }
            }
        }

    private fun applyDistance(
        side: Side,
        entry: BigDecimal,
        dist: BigDecimal,
        kind: ChildKind,
    ): BigDecimal {
        // Long stop is below entry; long take-profit above. Short is mirrored.
        val sign =
            when {
                kind == ChildKind.STOP_LOSS && side == Side.BUY -> -BigDecimal.ONE
                kind == ChildKind.STOP_LOSS && side == Side.SELL -> BigDecimal.ONE
                kind == ChildKind.TAKE_PROFIT && side == Side.BUY -> BigDecimal.ONE
                kind == ChildKind.TAKE_PROFIT && side == Side.SELL -> -BigDecimal.ONE
                else -> error("unreachable")
            }
        return entry.add(sign.multiply(dist, Money.CONTEXT), Money.CONTEXT)
    }
}

fun interface CompiledChildPrice {
    fun evaluate(
        ec: EvalContext,
        side: Side,
        entry: BigDecimal,
        stopDistance: BigDecimal?,
    ): BigDecimal
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ChildPriceResolverTest'
git add src/main/kotlin/com/qkt/dsl/compile/ChildPriceResolver.kt src/test/kotlin/com/qkt/dsl/compile/ChildPriceResolverTest.kt
git commit -m "feat(dsl): resolve child prices for bracket and oco"
```

---

### Task 3: `OrderTypeCompiler` — LIMIT, STOP, STOP-LIMIT, TRAILING

Compiles `OrderTypeAst` into a closure that builds an `OrderRequest` at fire time, plus an "entry price reference" closure used by BRACKET children. `Market` returns `candle.close` as its entry-price reference.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/OrderTypeCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/OrderTypeCompilerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderTypeCompilerTest {
    private fun ec(closePrice: String) =
        EvalContext(
            candle = Candle("BTCUSDT", BigDecimal(closePrice), BigDecimal(closePrice), BigDecimal(closePrice), BigDecimal(closePrice), BigDecimal.ZERO, 0L, 60_000L),
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private fun compiler() = OrderTypeCompiler(ExprCompiler())

    @Test
    fun `Market entry price is candle close`() {
        val c = compiler().compile(Market)
        val ctx = ec("100")
        assertThat(c.entryPrice.evaluate(ctx)).isEqualByComparingTo("100")
    }

    @Test
    fun `Limit builds Limit OrderRequest with absolute price`() {
        val c = compiler().compile(Limit(NumLit(BigDecimal("99.5"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-1",
                side = Side.BUY,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.Limit
        assertThat(req.limitPrice).isEqualByComparingTo("99.5")
        assertThat(req.symbol).isEqualTo("BTCUSDT")
        assertThat(req.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `Limit entryPrice equals the limit price`() {
        val c = compiler().compile(Limit(NumLit(BigDecimal("99.5"))))
        assertThat(c.entryPrice.evaluate(ec("100"))).isEqualByComparingTo("99.5")
    }

    @Test
    fun `Stop builds Stop OrderRequest`() {
        val c = compiler().compile(Stop(NumLit(BigDecimal("95"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-2",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.Stop
        assertThat(req.stopPrice).isEqualByComparingTo("95")
    }

    @Test
    fun `StopLimit builds StopLimit OrderRequest`() {
        val c = compiler().compile(StopLimit(NumLit(BigDecimal("95")), NumLit(BigDecimal("94"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-3",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.StopLimit
        assertThat(req.stopPrice).isEqualByComparingTo("95")
        assertThat(req.limitPrice).isEqualByComparingTo("94")
    }

    @Test
    fun `TrailingBy builds TrailingStop with ABSOLUTE mode`() {
        val c = compiler().compile(TrailingBy(NumLit(BigDecimal("3"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-4",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.TrailingStop
        assertThat(req.trailMode).isEqualTo(TrailMode.ABSOLUTE)
        assertThat(req.trailAmount).isEqualByComparingTo("3")
    }

    @Test
    fun `TrailingPct builds TrailingStop with PERCENT mode and 0-100 scale`() {
        val c = compiler().compile(TrailingPct(NumLit(BigDecimal("0.05"))))
        val req =
            c.buildRequest.evaluate(
                ec = ec("100"),
                id = "id-5",
                side = Side.SELL,
                qty = BigDecimal.ONE,
                tif = TimeInForce.GTC,
                strategyId = "s",
                ts = 0L,
            ) as OrderRequest.TrailingStop
        assertThat(req.trailMode).isEqualTo(TrailMode.PERCENT)
        // 0.05 fraction → 5.0 percent
        assertThat(req.trailAmount).isEqualByComparingTo("5")
    }
}
```

- [ ] **Step 2: Implement `OrderTypeCompiler.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import java.math.BigDecimal

data class CompiledOrderType(
    val buildRequest: BuildRequest,
    val entryPrice: EntryPriceRef,
)

fun interface EntryPriceRef {
    fun evaluate(ec: EvalContext): BigDecimal
}

fun interface BuildRequest {
    fun evaluate(
        ec: EvalContext,
        id: String,
        side: Side,
        qty: BigDecimal,
        tif: TimeInForce,
        strategyId: String,
        ts: Long,
    ): OrderRequest
}

class OrderTypeCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(ot: OrderTypeAst): CompiledOrderType =
        when (ot) {
            Market -> compileMarket()
            is Limit -> compileLimit(ot)
            is Stop -> compileStop(ot)
            is StopLimit -> compileStopLimit(ot)
            is TrailingBy -> compileTrailingBy(ot)
            is TrailingPct -> compileTrailingPct(ot)
        }

    private fun compileMarket(): CompiledOrderType {
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                OrderRequest.Market(
                    id = id,
                    symbol = ec.candle.symbol,
                    side = side,
                    quantity = qty,
                    timeInForce = tif,
                    timestamp = ts,
                    strategyId = strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }

    private fun compileLimit(o: Limit): CompiledOrderType {
        val priceEval = exprCompiler.compile(o.price)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val p = (priceEval.evaluate(ec) as Value.Num).v
                OrderRequest.Limit(id, ec.candle.symbol, side, qty, p, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (priceEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileStop(o: Stop): CompiledOrderType {
        val priceEval = exprCompiler.compile(o.price)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val p = (priceEval.evaluate(ec) as Value.Num).v
                OrderRequest.Stop(id, ec.candle.symbol, side, qty, p, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (priceEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileStopLimit(o: StopLimit): CompiledOrderType {
        val stopEval = exprCompiler.compile(o.stopPrice)
        val limitEval = exprCompiler.compile(o.limitPrice)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val sp = (stopEval.evaluate(ec) as Value.Num).v
                val lp = (limitEval.evaluate(ec) as Value.Num).v
                OrderRequest.StopLimit(id, ec.candle.symbol, side, qty, sp, lp, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (stopEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileTrailingBy(o: TrailingBy): CompiledOrderType {
        val distEval = exprCompiler.compile(o.distance)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val d = (distEval.evaluate(ec) as Value.Num).v
                OrderRequest.TrailingStop(id, ec.candle.symbol, side, qty, d, TrailMode.ABSOLUTE, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }

    private fun compileTrailingPct(o: TrailingPct): CompiledOrderType {
        val fracEval = exprCompiler.compile(o.frac)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val f = (fracEval.evaluate(ec) as Value.Num).v
                val percent = f.multiply(BigDecimal("100"), Money.CONTEXT)
                OrderRequest.TrailingStop(id, ec.candle.symbol, side, qty, percent, TrailMode.PERCENT, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.OrderTypeCompilerTest'
git add src/main/kotlin/com/qkt/dsl/compile/OrderTypeCompiler.kt src/test/kotlin/com/qkt/dsl/compile/OrderTypeCompilerTest.kt
git commit -m "feat(dsl): compile order-type AST to engine OrderRequest builders"
```

---

### Task 4: `SizingCompiler` — qty, USD, RISK $, POSITION.<sym>

Computes quantity at fire time. Variants:
- `SizeQty(expr)` — direct (existing 11b path)
- `SizeNotional(usd)` — `qty = usd / entryPrice`
- `SizeRiskAbs(amount)` — `qty = amount / abs(entry - stop)`. Compile-time error if no stop.
- `SizePositionFull(stream)` — `qty = abs(positionFor(stream).quantity)`. If flat: empty list / no fire.

Out of scope for 11d1: `SizePctEquity`, `SizePctBalance`, `SizeRiskFrac` — defer to 11d2.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/SizingCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/SizingCompilerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SizingCompilerTest {
    private val ec =
        EvalContext(
            candle = Candle("BTCUSDT", BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal.ZERO, 0L, 1L),
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private fun compiler() = SizingCompiler(ExprCompiler())

    @Test
    fun `SizeQty returns the expression`() {
        val s = compiler().compile(SizeQty(NumLit(BigDecimal("3"))), stopDistance = null)
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("3")
    }

    @Test
    fun `SizeNotional divides USD by entry price`() {
        val s = compiler().compile(SizeNotional(NumLit(BigDecimal("500"))), stopDistance = null)
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("5")
    }

    @Test
    fun `SizeRiskAbs computes quantity from amount and stop distance`() {
        val s = compiler().compile(SizeRiskAbs(NumLit(BigDecimal("50"))), stopDistance = BigDecimal("5"))
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("10")
    }

    @Test
    fun `SizeRiskAbs without stop distance errors at compile time`() {
        assertThatThrownBy {
            compiler().compile(SizeRiskAbs(NumLit(BigDecimal("50"))), stopDistance = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `SizePositionFull returns absolute quantity when flat`() {
        val s = compiler().compile(SizePositionFull("btc"), stopDistance = null)
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("0")
    }

    @Test
    fun `SizePctEquity is deferred to 11d2`() {
        assertThatThrownBy {
            compiler().compile(SizePctEquity(NumLit(BigDecimal("0.01"))), stopDistance = null)
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("deferred")
    }

    @Test
    fun `SizeRiskFrac is deferred to 11d2`() {
        assertThatThrownBy {
            compiler().compile(SizeRiskFrac(NumLit(BigDecimal("0.01"))), stopDistance = BigDecimal("5"))
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("deferred")
    }
}
```

- [ ] **Step 2: Implement `SizingCompiler.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import java.math.BigDecimal

fun interface CompiledSize {
    fun evaluate(
        ec: EvalContext,
        entryPrice: BigDecimal,
    ): BigDecimal
}

class SizingCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(
        sizing: SizingAst,
        stopDistance: BigDecimal?,
    ): CompiledSize =
        when (sizing) {
            is SizeQty -> {
                val e = exprCompiler.compile(sizing.expr)
                CompiledSize { ec, _ -> (e.evaluate(ec) as Value.Num).v }
            }
            is SizeNotional -> {
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, entry ->
                    val usd = (e.evaluate(ec) as Value.Num).v
                    usd.divide(entry, Money.CONTEXT)
                }
            }
            is SizeRiskAbs -> {
                require(stopDistance != null && stopDistance.signum() > 0) {
                    "SIZING RISK \$ requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, _ ->
                    val amount = (e.evaluate(ec) as Value.Num).v
                    amount.divide(stopDistance, Money.CONTEXT)
                }
            }
            is SizePositionFull -> {
                CompiledSize { ec, _ ->
                    val symbol =
                        ec.streamSymbols[sizing.stream]
                            ?: error("Unknown stream alias: ${sizing.stream}")
                    val qty =
                        ec.strategyContext.positions
                            .positionFor(symbol)
                            ?.quantity
                            ?.abs()
                            ?: BigDecimal.ZERO
                    qty
                }
            }
            is SizePctEquity, is SizePctBalance, is SizeRiskFrac ->
                error(
                    "Sizing mode ${sizing::class.simpleName} is deferred to Phase 11d2 — " +
                        "needs engine equity/balance surface",
                )
        }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.SizingCompilerTest'
git add src/main/kotlin/com/qkt/dsl/compile/SizingCompiler.kt src/test/kotlin/com/qkt/dsl/compile/SizingCompilerTest.kt
git commit -m "feat(dsl): compile equity-free sizing modes; defer percent and risk-fraction"
```

---

### Task 5: BRACKET — wrap entry order with stop-loss + take-profit children

The hardest piece. The action callable for `BUY ... BRACKET { ... }` must:
1. Compile the entry `OrderTypeAst` (Task 3) → get build closure + entry-price ref.
2. Compile the bracket's stop-loss child (Task 2) — kind = STOP_LOSS.
3. Compile the bracket's take-profit child (Task 2) — kind = TAKE_PROFIT.
4. Compile sizing (Task 4), passing the resolvable stop distance if BRACKET STOP LOSS is `BY` or `PCT` (we know the distance at compile time for those modes; for `AT` we'd compute at fire time — for 11d1 the simple path is to require BY/PCT for risk-based sizing).
5. At fire time: evaluate entry price → evaluate stop & TP → build `OrderRequest.Bracket(...)` → emit `Signal.Submit`.

For risk-based sizing where the stop is `AT <price>`, we need to evaluate the stop price *before* sizing — at fire time. To keep things tractable for 11d1, only `BY <expr>` and `PCT <expr>` stop modes feed `RISK $` sizing; `AT <price>` with `RISK $` errors at compile time.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/BracketCompileTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class BracketCompileTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `BUY market with BRACKET BY-RR builds Bracket OrderRequest`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal.ONE)),
                        bracket =
                            BracketAst(
                                stopLoss = ChildBy(NumLit(BigDecimal("5"))),
                                takeProfit = ChildRr(NumLit(BigDecimal("3"))),
                            ),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).hasSize(1)
        val sig = sigs[0] as Signal.Submit
        val br = sig.request as OrderRequest.Bracket
        // entry market at price 100, stop -5 = 95, RR 3 → take-profit = 100 + 3*5 = 115
        assertThat(br.stopLoss).isEqualByComparingTo("95")
        assertThat(br.takeProfit).isEqualByComparingTo("115")
        assertThat(br.entry).isInstanceOf(OrderRequest.Market::class.java)
    }
}
```

- [ ] **Step 2: Extend `ActionCompiler` to wire BRACKET**

The biggest single edit. The Buy/Sell branch needs to:
1. Determine if `opts.orderType` is non-null and non-Market (or any non-default options). Pick the OrderTypeCompiler path.
2. Determine if `opts.bracket` is non-null. If so, wrap with Bracket.
3. Determine if `opts.oco` is non-null. If so, wrap with StandaloneOCO (Task 6).
4. Compile sizing with whatever stop-distance we can resolve from the bracket.

Sketch of the new BUY/SELL compile:

```kotlin
private fun compileBuySell(
    stream: String,
    opts: ActionOpts,
    side: Side,
): (EvalContext) -> List<Signal> {
    require(opts.tif == null || opts.tif !is Gtd) { "TIF GTD is deferred" }
    val tif = TifTranslator.translate(opts.tif)
    val orderType = opts.orderType ?: Market
    val orderTypeCompiler = OrderTypeCompiler(exprCompiler)
    val childResolver = ChildPriceResolver(exprCompiler)
    val sizingCompiler = SizingCompiler(exprCompiler)

    val compiledOrderType = orderTypeCompiler.compile(orderType)

    // Resolve stop distance for risk-based sizing
    val stopDistance: BigDecimal? = resolveStaticStopDistance(opts.bracket?.stopLoss)
    val sizingExpr = opts.sizing ?: error("BUY/SELL requires SIZING")
    val compiledSize = sizingCompiler.compile(sizingExpr, stopDistance)

    val compiledSL = opts.bracket?.stopLoss?.let { childResolver.compile(it, ChildKind.STOP_LOSS) }
    val compiledTP = opts.bracket?.takeProfit?.let { childResolver.compile(it, ChildKind.TAKE_PROFIT) }

    return { ec ->
        val symbol = ec.streamSymbols[stream] ?: error("Unknown stream alias: $stream")
        val ts = ec.strategyContext.clock.now()
        val entry = compiledOrderType.entryPrice.evaluate(ec)
        val qty = compiledSize.evaluate(ec, entry)
        val entryReq = compiledOrderType.buildRequest.evaluate(ec, ids.next(), side, qty, tif, "", ts)

        val request: OrderRequest =
            if (opts.bracket != null) {
                val sl = requireNotNull(compiledSL) { "BRACKET requires STOP LOSS" }
                val tp = requireNotNull(compiledTP) { "BRACKET requires TAKE PROFIT" }
                val slPrice = sl.evaluate(ec, side, entry, stopDistance = null)
                val sd = (entry - slPrice).abs()
                val tpPrice = tp.evaluate(ec, side, entry, stopDistance = sd)
                OrderRequest.Bracket(
                    id = ids.next(),
                    symbol = symbol,
                    side = side,
                    quantity = qty,
                    entry = entryReq,
                    takeProfit = tpPrice,
                    stopLoss = slPrice,
                    timeInForce = tif,
                    timestamp = ts,
                )
            } else {
                entryReq
            }

        listOf(Signal.Submit(request))
    }
}
```

`resolveStaticStopDistance` returns the stop distance if we can compute it without the entry price (BY/PCT children with constant or LET-resolved expressions; AT children return null). For 11d1 the simplest is to support `ChildBy(NumLit)` and `ChildPct` only — for anything more sophisticated, throw "RISK \$ with this stop mode is not supported in 11d1; upgrade to 11d2 or use a constant BY".

Concrete (constants only):

```kotlin
private fun resolveStaticStopDistance(stop: ChildPriceAst?): BigDecimal? =
    when (stop) {
        is ChildBy -> {
            val expr = stop.distance
            if (expr is NumLit) expr.value else null
        }
        is ChildPct -> null // depends on entry — defer to fire time, can't statically resolve
        else -> null
    }
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.BracketCompileTest'
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/BracketCompileTest.kt
git commit -m "feat(dsl): compile BRACKET with child stop loss and take profit"
```

---

### Task 6: OCO — two-leg order

`OCO { STOP AT 90, LIMIT AT 110 }` → `OrderRequest.StandaloneOCO(leg1 = Stop, leg2 = Limit)`. Each leg uses the same OrderTypeCompiler infrastructure.

The DSL spec says `OCO { STOP AT <expr>, LIMIT AT <expr> }`. The two legs are exit orders for an existing position — they don't bracket an entry. So `BUY btc OCO { ... }` is unusual; `OCO` is more typically a separate rule action or attached to an exit.

For 11d1 we ship: `BUY btc OCO { STOP AT <s>, LIMIT AT <l> }` is interpreted as: enter, then attach two opposite-side exit orders — one stop, one limit — linked so one cancels the other when filled. The engine's `StandaloneOCO` wraps two arbitrary orders.

Wait — re-reading the engine surface. `StandaloneOCO` has `leg1: OrderRequest, leg2: OrderRequest` — two arbitrary orders. The DSL OCO syntax fits this, with `STOP AT <s>` building a Stop leg and `LIMIT AT <l>` building a Limit leg, both with side opposite to the entry.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/OcoCompileTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class OcoCompileTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `BUY with OCO STOP-LIMIT builds StandaloneOCO`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal.ONE)),
                        oco =
                            OcoAst(
                                stop = ChildAt(NumLit(BigDecimal("90"))),
                                limit = ChildAt(NumLit(BigDecimal("110"))),
                            ),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).hasSize(1)
        val req = (sigs[0] as Signal.Submit).request as OrderRequest.StandaloneOCO
        assertThat(req.leg1).isInstanceOf(OrderRequest.Stop::class.java)
        assertThat(req.leg2).isInstanceOf(OrderRequest.Limit::class.java)
    }
}
```

- [ ] **Step 2: Add OCO branch in `ActionCompiler.compileBuySell`**

Replace the bracket-only post-entry handling with branched bracket-or-oco logic. For OCO, build two legs with side opposite to entry side, wrap in `StandaloneOCO`. (Detail: see Task 5 sketch; add an `else if (opts.oco != null)` branch.)

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.OcoCompileTest'
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/OcoCompileTest.kt
git commit -m "feat(dsl): compile OCO with two-leg StandaloneOCO"
```

---

### Task 7: Wire `IdGenerator` into `AstCompiler`

Each `CompiledStrategy` gets its own `SequentialIdGenerator(prefix = "dsl-${ast.name}-")`. Pass it into the `ActionCompiler` constructor; the action callables close over it.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`

- [ ] **Step 1: Add `ids` parameter to `ActionCompiler`**

```kotlin
class ActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger = LoggerFactory.getLogger("com.qkt.dsl.strategy"),
    private val ids: IdGenerator = SequentialIdGenerator(prefix = "dsl-anonymous-"),
)
```

- [ ] **Step 2: AstCompiler builds a per-strategy `SequentialIdGenerator`**

```kotlin
val ids = SequentialIdGenerator(prefix = "dsl-${ast.name}-")
val actionCompiler = ActionCompiler(exprCompiler, strategyLogger, ids)
```

- [ ] **Step 3: Run all DSL tests + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.*'
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt
git commit -m "feat(dsl): wire per-strategy IdGenerator into action compilation"
```

---

### Task 8: Kotlin DSL — order type builders

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/OrderTypes.kt`

- [ ] **Step 1: Implement `OrderTypes.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

val market: OrderTypeAst = Market

fun limitAt(price: ExprAst): OrderTypeAst = Limit(price)

fun stopAt(price: ExprAst): OrderTypeAst = Stop(price)

fun stopLimit(stop: ExprAst, limit: ExprAst): OrderTypeAst = StopLimit(stop, limit)

fun trailingBy(distance: ExprAst): OrderTypeAst = TrailingBy(distance)

fun trailingPct(frac: ExprAst): OrderTypeAst = TrailingPct(frac)
```

- [ ] **Step 2: Extend `ActionScope.buy/sell` to take optional clauses**

Add overloaded `buy`/`sell` that accept `orderType`, `tif`, `bracket`, `oco`. Use named parameters with sensible defaults. (Detail: ActionScope grows from 3 fns to ~8 fns.)

- [ ] **Step 3: Run + commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/com/qkt/dsl/kotlin/OrderTypes.kt src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt
git commit -m "feat(dsl): kotlin builders for order types and action options"
```

---

### Task 9: Kotlin DSL — child price + bracket + oco builders

`childAt(95.bd)`, `childBy(5.bd)`, `childPct(0.05.bd)`, `childRr(3.bd)` produce `ChildPriceAst`. `bracket(stopLoss = ..., takeProfit = ...)` and `oco(stop = ..., limit = ...)` produce `BracketAst` / `OcoAst`.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Brackets.kt`

- [ ] **Step 1: Implement and commit**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OcoAst

fun childAt(price: ExprAst): ChildPriceAst = ChildAt(price)

fun childBy(distance: ExprAst): ChildPriceAst = ChildBy(distance)

fun childPct(frac: ExprAst): ChildPriceAst = ChildPct(frac)

fun childRr(multiplier: ExprAst): ChildPriceAst = ChildRr(multiplier)

fun bracket(
    stopLoss: ChildPriceAst,
    takeProfit: ChildPriceAst,
): BracketAst = BracketAst(stopLoss, takeProfit)

fun oco(
    stop: ChildPriceAst,
    limit: ChildPriceAst,
): OcoAst = OcoAst(stop, limit)
```

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/Brackets.kt
git commit -m "feat(dsl): kotlin builders for child prices brackets and oco"
```

---

### Task 10: Kotlin DSL — sizing + TIF builders

```kotlin
fun usdNotional(usd: ExprAst): SizingAst = SizeNotional(usd)
fun riskAbs(amount: ExprAst): SizingAst = SizeRiskAbs(amount)
fun positionFull(stream: StreamRef): SizingAst = SizePositionFull(stream.alias)

val gtc: TifAst = Gtc
val ioc: TifAst = Ioc
val fok: TifAst = Fok
val day: TifAst = Day
```

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/SizingAndTif.kt`

- [ ] **Step 1: Implement and commit**

(Code as above.)

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/SizingAndTif.kt
git commit -m "feat(dsl): kotlin builders for sizing modes and TIF constants"
```

---

### Task 11: End-to-end test — LIMIT entry + BRACKET

A strategy that:
1. Submits a LIMIT entry at `close - 1` when fast crosses above slow.
2. Brackets it with `STOP LOSS BY 5, TAKE PROFIT RR 3`.
3. Sizes with `RISK $50`.
4. Ends with at least one BUY trade and verifies the bracket attached.

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/OrderSurfaceEndToEndTest.kt`

- [ ] **Step 1: Implement the test**

A meaningful test asserts on the OrderRequest types submitted (via a test broker that captures Submit signals) rather than on Backtest trade output, because the existing PaperBroker may not fully simulate brackets and stop fills. For 11d1 we test the *compilation* end-to-end: build the AST, compile, evaluate the action, assert the emitted `Signal.Submit(OrderRequest.Bracket(...))` shape with correct prices.

```kotlin
@Test
fun `dsl LIMIT entry with BRACKET emits Submit Bracket signal`() {
    val ast = strategy("limit_bracket", version = 1) {
        val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
        val fast by letting(ema(btc.close, period = 3))
        val slow by letting(ema(btc.close, period = 7))
        rule {
            whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
            then {
                buy(
                    btc,
                    qty = 1.bd,
                    orderType = limitAt(99.bd),
                    bracket = bracket(stopLoss = childBy(5.bd), takeProfit = childRr(3.bd)),
                )
            }
        }
    }
    val strategy = AstCompiler().compile(ast)
    // exercise via Backtest, capturing emit(Signal.Submit(...)) externally — see test detail
    // ...
}
```

The full implementation needs a hook to capture emit calls. The plan-execution agent will write this against the existing test infrastructure.

- [ ] **Step 2: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.OrderSurfaceEndToEndTest'
git add src/test/kotlin/com/qkt/dsl/compile/OrderSurfaceEndToEndTest.kt
git commit -m "test(dsl): end-to-end LIMIT entry with BRACKET"
```

---

### Task 12: Refresh boundary lock

11c-era tests asserted that LIMIT/STOP/TIF/BRACKET/OCO were rejected. Most are now supported. Update:

- Drop "non-market order type is rejected" — LIMIT/STOP/TRAILING work now.
- Drop "TIF is not supported" — DAY/GTC/IOC/FOK work; only GTD rejected.
- Drop "BRACKET is not supported" — works now.
- Drop "OCO is not supported" — works now.
- Drop "non-quantity sizing is rejected" — USD/RISK\$/POSITION-FULL work; %-of-equity/RISK-frac still rejected.
- Add "GTD is deferred" — TifTranslator throws.
- Add "%-of-equity / RISK-fraction is deferred to 11d2" — SizingCompiler throws.

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt`

- [ ] **Step 1: Edit the test file**

Remove the 5 "is unsupported" tests for LIMIT/TIF/BRACKET/OCO/non-qty sizing. Keep "Buy without sizing is rejected". The compiled-action plumbing tests in 11c3's `ActionCompilerExtensionsTest` cover the new surface.

(`SizingCompilerTest` and `TifTranslatorTest` already cover the deferred-message cases.)

- [ ] **Step 2: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerTest'
git add src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt
git commit -m "test(dsl): refresh action boundary lock for 11d1"
```

---

### Task 13: Build green + ktlint

- [ ] `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew ktlintFormat` if needed; commit reformat as `style:`.
- [ ] Re-run `./gradlew build`.

---

### Task 14: Phase changelog

**Files:**
- Create: `docs/phases/phase-11d1-order-surface.md`

Cover: summary, what's new (every order type + brackets + OCO + child prices + sizing modes + TIF + per-strategy ID generation), migration (`Signal.Submit` for non-trivial orders; same backward-compatible Signal.Buy/Sell for plain market), usage cookbook (LIMIT entry, BRACKET trailing, OCO exits, RISK $ sizing, TRAILING stop), testing patterns, known limitations (% OF EQUITY / RISK fraction / DEFAULTS deferred to 11d2; GTD deferred; RISK $ requires static stop distance), references.

- [ ] **Step 1: Commit**

```bash
git add docs/phases/phase-11d1-order-surface.md
git commit -m "docs: phase 11d1 changelog"
```

---

### Task 15: Pre-push checklist

Per qkt skill §4. Hand off to `superpowers:finishing-a-development-branch`. Merge with `merge: phase 11d1 order surface`. Fill in the merge SHA on `main` afterward.

---

### Task 16: Cleanup notes

After 11d1 merges:
- Verify no production code imports `Signal.Buy`/`Signal.Sell` directly from DSL output (they may; that's fine — Buy/Sell still flow through). The DSL emits Submit only for non-default cases.
- Note in 11d2's plan that `Account.equity` / `Account.balance` accessors that are currently rejected at compile time become supported.
