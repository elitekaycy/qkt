# Phase 11c3 — Log, CLOSE, CLOSE_ALL Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Round out the action surface beyond `BUY`/`SELL`. Ship `Log "<msg>"` (emits via SLF4J), `CLOSE <stream>` (flatten one position by inferring direction from current quantity), and `CLOSE_ALL` (flatten every open position the strategy holds). `CANCEL <stream>` / `CANCEL_ALL` are deferred — the engine has per-broker `cancel(orderId)` but no "cancel-all-for-symbol" surface, and strategies don't track their own orderIds. The deferred actions remain in the AST and produce a clear "not supported" error at compile time.

**Architecture:** One ripple change to fan-out: `ActionCompiler.compile(action)` returns `(EvalContext) -> List<Signal>` instead of `(EvalContext) -> Signal`. `Log` returns an empty list (after logging). `CLOSE` returns 0 or 1 signals depending on current position. `CLOSE_ALL` returns N signals, one per open position in the strategy's `positions` view. `CompiledRule.fire` returns `List<Signal>`; `CompiledStrategy.onCandle` `for (s in fire(...)) emit(s)`. Snapshot capture continues to fire on Buy/Sell actions only — Log doesn't capture, CLOSE/CLOSE_ALL emit Sell/Buy signals so they participate in the @sell/@buy capture pathway when appropriate.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle, SLF4J (already a dependency). No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 — Phase 11c3.

**Branch:** `phase11c3-actions` (already cut from `main`).

---

## Design notes

### Why `(EvalContext) -> List<Signal>` instead of `Signal?`

`CLOSE_ALL` may emit multiple signals from one rule fire (one per open position). The current `Signal?` return cannot express this. Changing to `List<Signal>` is a small ripple:
- `Buy` → `listOf(Signal.Buy(...))`
- `Sell` → `listOf(Signal.Sell(...))`
- `Log` → emit log line, return `emptyList()`
- `CLOSE` (long position) → `listOf(Signal.Sell(symbol, qty))`
- `CLOSE` (short position) → `listOf(Signal.Buy(symbol, -qty))`
- `CLOSE` (flat) → `emptyList()`
- `CLOSE_ALL` → list per non-zero position

`CompiledRule.fire(...)` returns `List<Signal>` (empty when condition false or action emits nothing). `CompiledStrategy.onCandle` iterates and emits each.

### CLOSE direction inference

`CLOSE btc` reads `positions.positionFor(symbol).quantity`:
- Positive (long): emit `Signal.Sell(symbol, quantity)`.
- Negative (short): emit `Signal.Buy(symbol, quantity.abs())`.
- Zero: emit nothing. (User can guard with `position(btc) gt 0.bd` if they want.)

### CLOSE_ALL scope

`CLOSE_ALL` iterates `ctx.strategyContext.positions.allPositions()` — only positions held by **this strategy**. Positions held by other strategies in the same engine are not affected. Each non-zero position emits one closing signal as above.

### Why CANCEL is deferred

Cancellation is per-orderId. The DSL strategy doesn't currently track which orderIds it submitted, and the broker interface has no "cancel all for symbol" call. Adding either is meaningful infra work (probably belongs alongside the Phase 11d order-lifecycle expansion). For 11c3, `Cancel` and `CancelAll` AST nodes remain declared; their compile branches throw with a clear deferred message.

### Logger naming

The DSL strategy compiles to a `CompiledStrategy` instance. We use `LoggerFactory.getLogger("com.qkt.dsl.strategy.${ast.name}")` — predictable, filter-able, and aligned with the engine's existing SLF4J patterns. The strategy name is in the `StrategyAst`, so the logger gets created at compile time and closed-over by the Log action.

---

## File Structure

### New files

```
src/test/kotlin/com/qkt/dsl/compile/
└── ActionCompilerExtensionsTest.kt      # Log + CLOSE + CLOSE_ALL + deferred Cancel cases

src/test/kotlin/com/qkt/dsl/compile/
└── CloseEndToEndTest.kt                 # full strategy: enter on cross, CLOSE on drawdown threshold
```

### Modified files

```
src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt   # signature change + new action branches
src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt     # fire returns List<Signal>
src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt      # CompiledStrategy.onCandle iterates list
src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt       # log/closeStream/closeAll builders
src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt   # adapt for new return shape
```

---

## Tasks

### Task 1: Action callable returns `List<Signal>`

`ActionCompiler.compile(action)` returns `(EvalContext) -> List<Signal>`. Buy/Sell wrap their existing single signal in a singleton list. `CompiledRule.fire` returns `List<Signal>`. `CompiledStrategy.onCandle` iterates.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt`

- [ ] **Step 1: `ActionCompiler` returns `List<Signal>`**

```kotlin
class ActionCompiler(private val exprCompiler: ExprCompiler) {
    fun compile(action: ActionAst): (EvalContext) -> List<Signal> =
        when (action) {
            is Buy -> compileBuySell(action.stream, action.opts) { sym, qty -> Signal.Buy(sym, qty) }
            is Sell -> compileBuySell(action.stream, action.opts) { sym, qty -> Signal.Sell(sym, qty) }
            else -> error("Action ${action::class.simpleName} is not supported in 11c2")
        }

    private fun compileBuySell(
        stream: String,
        opts: ActionOpts,
        ctor: (String, BigDecimal) -> Signal,
    ): (EvalContext) -> List<Signal> {
        // existing validation unchanged
        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING in 11b")
        require(sizing is SizeQty) { "Only direct quantity sizing is supported in 11b" }
        require(opts.orderType == null || opts.orderType == Market) { "Only MARKET order type is supported in 11b" }
        require(opts.tif == null) { "TIF is not supported in 11b" }
        require(opts.bracket == null) { "BRACKET is not supported in 11b" }
        require(opts.oco == null) { "OCO is not supported in 11b" }
        val qtyExpr = exprCompiler.compile(sizing.expr)
        return { ctx ->
            val symbol = ctx.streamSymbols[stream] ?: error("Unknown stream alias: $stream")
            val v = qtyExpr.evaluate(ctx)
            require(v is Value.Num) { "SIZING must be numeric, got $v" }
            listOf(ctor(symbol, v.v))
        }
    }
}
```

- [ ] **Step 2: `CompiledRule.fire` returns `List<Signal>`**

Replace the `fire` body:

```kotlin
fun fire(
    ec: EvalContext,
    ctx: StrategyContext,
): List<Signal> {
    val v = condition.evaluate(ec)
    if (v !is Value.Bool || !v.v) return emptyList()

    val preFireQty = ctx.positions.positionFor(ruleSymbol)?.quantity ?: BigDecimal.ZERO
    val isOpening = preFireQty.signum() == 0 && (isBuy || isSell)

    if (isBuy) capture(onBuyCaptures, SnapshotBuy, ec)
    if (isSell) capture(onSellCaptures, SnapshotSell, ec)
    if (isOpening) capture(onOpenCaptures, SnapshotOpen, ec)

    return action(ec)
}
```

The `action` field type changes from `(EvalContext) -> Signal` to `(EvalContext) -> List<Signal>`.

- [ ] **Step 3: `CompiledStrategy.onCandle` iterates**

```kotlin
for (rule in rules) {
    for (sig in rule.fire(ec, ctx)) emit(sig)
}
```

- [ ] **Step 4: Adapt existing `ActionCompilerTest`**

Existing assertions like `assertThat(sig).isEqualTo(Signal.Buy(...))` change to `assertThat(sig).containsExactly(Signal.Buy(...))`. The "Close action is unsupported in 11b" test needs to be deleted — Close will be supported by Task 3.

- [ ] **Step 5: Run all DSL tests**

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: PASS — Buy/Sell tests adapt, end-to-end equivalence tests still match.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt
git commit -m "refactor(dsl): action callables return List of Signal"
```

---

### Task 2: Compile `Log`

The `Log` action takes a static message string at parse time. The compiler closes over an SLF4J logger named after the strategy.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` — pass strategy name into `ActionCompiler`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt`

- [ ] **Step 1: Pass strategy name to `ActionCompiler`**

`ActionCompiler` constructor gains `strategyLogger: org.slf4j.Logger`. `AstCompiler` builds `LoggerFactory.getLogger("com.qkt.dsl.strategy.${ast.name}")` and passes it in.

- [ ] **Step 2: Add `Log` branch**

```kotlin
when (action) {
    is Buy -> ...
    is Sell -> ...
    is Log -> compileLog(action)
    else -> error("Action ${action::class.simpleName} is not supported in 11c3")
}

private fun compileLog(log: com.qkt.dsl.ast.Log): (EvalContext) -> List<Signal> {
    val msg = log.message
    return { _ ->
        strategyLogger.info(msg)
        emptyList()
    }
}
```

- [ ] **Step 3: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ActionCompilerExtensionsTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `Log emits no signals`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(Log("entered long"))
                .invoke(ctx)
        assertThat(sigs).isEmpty()
    }

    @Test
    fun `Buy still wraps single signal in list`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))))
                .invoke(ctx)
        assertThat(sigs).hasSize(1)
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerExtensionsTest'
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt
git commit -m "feat(dsl): compile Log action via SLF4J"
```

---

### Task 3: Compile `CLOSE`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt`

- [ ] **Step 1: Add the failing tests**

Append to `ActionCompilerExtensionsTest.kt`:

```kotlin
    @Test
    fun `CLOSE on long emits Sell at full quantity`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BTCUSDT") {
                        com.qkt.positions.Position("BTCUSDT", BigDecimal("2.5"), BigDecimal("100"))
                    } else {
                        null
                    }

                override fun allPositions() = mapOf("BTCUSDT" to com.qkt.positions.Position("BTCUSDT", BigDecimal("2.5"), BigDecimal("100")))
            }
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(com.qkt.dsl.ast.Close("btc"))
                .invoke(ec)
        assertThat(sigs).containsExactly(com.qkt.strategy.Signal.Sell("BTCUSDT", BigDecimal("2.5")))
    }

    @Test
    fun `CLOSE on short emits Buy at absolute quantity`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BTCUSDT") {
                        com.qkt.positions.Position("BTCUSDT", BigDecimal("-1.5"), BigDecimal("100"))
                    } else {
                        null
                    }

                override fun allPositions() = mapOf("BTCUSDT" to com.qkt.positions.Position("BTCUSDT", BigDecimal("-1.5"), BigDecimal("100")))
            }
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(com.qkt.dsl.ast.Close("btc"))
                .invoke(ec)
        assertThat(sigs).containsExactly(com.qkt.strategy.Signal.Buy("BTCUSDT", BigDecimal("1.5")))
    }

    @Test
    fun `CLOSE on flat emits no signals`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(com.qkt.dsl.ast.Close("btc"))
                .invoke(ctx)
        assertThat(sigs).isEmpty()
    }
```

- [ ] **Step 2: Add `Close` branch in `ActionCompiler`**

```kotlin
    is com.qkt.dsl.ast.Close -> compileClose(action.stream)
```

```kotlin
    private fun compileClose(streamAlias: String): (EvalContext) -> List<Signal> =
        { ctx ->
            val symbol = ctx.streamSymbols[streamAlias] ?: error("Unknown stream alias: $streamAlias")
            val qty = ctx.strategyContext.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            when {
                qty.signum() > 0 -> listOf(Signal.Sell(symbol, qty))
                qty.signum() < 0 -> listOf(Signal.Buy(symbol, qty.abs()))
                else -> emptyList()
            }
        }
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerExtensionsTest'
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt
git commit -m "feat(dsl): compile CLOSE action with direction inference"
```

---

### Task 4: Compile `CLOSE_ALL`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
    @Test
    fun `CLOSE_ALL emits one signal per non-zero position`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) = allPositions()[symbol]

                override fun allPositions() =
                    mapOf(
                        "BTCUSDT" to com.qkt.positions.Position("BTCUSDT", BigDecimal("2"), BigDecimal("100")),
                        "ETHUSDT" to com.qkt.positions.Position("ETHUSDT", BigDecimal("-3"), BigDecimal("50")),
                        "ZERO" to com.qkt.positions.Position("ZERO", BigDecimal.ZERO, BigDecimal("10")),
                    )
            }
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(com.qkt.dsl.ast.CloseAll)
                .invoke(ec)
        assertThat(sigs).containsExactlyInAnyOrder(
            com.qkt.strategy.Signal.Sell("BTCUSDT", BigDecimal("2")),
            com.qkt.strategy.Signal.Buy("ETHUSDT", BigDecimal("3")),
        )
    }
```

- [ ] **Step 2: Add `CloseAll` branch**

```kotlin
    is com.qkt.dsl.ast.CloseAll -> compileCloseAll()
```

```kotlin
    private fun compileCloseAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            val out = mutableListOf<Signal>()
            for ((symbol, position) in ctx.strategyContext.positions.allPositions()) {
                val qty = position.quantity
                when {
                    qty.signum() > 0 -> out.add(Signal.Sell(symbol, qty))
                    qty.signum() < 0 -> out.add(Signal.Buy(symbol, qty.abs()))
                }
            }
            out
        }
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerExtensionsTest'
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt
git commit -m "feat(dsl): compile CLOSE_ALL action"
```

---

### Task 5: Defer `CANCEL` and `CANCEL_ALL` with explicit error

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
    @Test
    fun `CANCEL is deferred with a clear message`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            ActionCompiler(ExprCompiler(), logger).compile(com.qkt.dsl.ast.Cancel("btc"))
        }.isInstanceOf(IllegalStateException::class.java)
         .hasMessageContaining("deferred")
    }

    @Test
    fun `CANCEL_ALL is deferred with a clear message`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            ActionCompiler(ExprCompiler(), logger).compile(com.qkt.dsl.ast.CancelAll)
        }.isInstanceOf(IllegalStateException::class.java)
         .hasMessageContaining("deferred")
    }
```

- [ ] **Step 2: Add explicit branches**

```kotlin
    is com.qkt.dsl.ast.Cancel ->
        error("CANCEL action is deferred — engine cancellation API needs broker-side surface; revisit alongside Phase 11d order lifecycle work")
    is com.qkt.dsl.ast.CancelAll ->
        error("CANCEL_ALL action is deferred — engine cancellation API needs broker-side surface; revisit alongside Phase 11d order lifecycle work")
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerExtensionsTest'
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt
git commit -m "feat(dsl): defer CANCEL and CANCEL_ALL with clear message"
```

---

### Task 6: Kotlin DSL builders

`ActionScope.log("msg")`, `ActionScope.closeStream(ref)`, `ActionScope.closeAll()`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`

- [ ] **Step 1: Append builders**

```kotlin
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.Log

object ActionScope {
    fun buy(...): ActionAst = ...
    fun sell(...): ActionAst = ...

    fun log(message: String): ActionAst = Log(message)

    fun closeStream(stream: StreamRef): ActionAst = Close(stream.alias)

    fun closeAll(): ActionAst = CloseAll
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt
git commit -m "feat(dsl): kotlin builders for log close and closeAll"
```

---

### Task 7: End-to-end test — drawdown stops trading

A strategy that:
1. Enters long on EMA cross above (with position-flat guard).
2. Logs the entry.
3. Closes the position when account drawdown (here approximated as `total_pnl lt -10`) crosses the threshold.

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/CloseEndToEndTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.Account
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CloseEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    @Test
    fun `close on negative pnl exits long position`() {
        val sample =
            ticks(
                listOf(
                    "100", "102", "104", "108", "112",   // ramp up
                    "115", "118", "120", "122", "125",   // continue
                    "120", "115", "110", "105", "100",   // drop
                    "95", "92", "90", "88", "85",        // deeper drop — drawdown triggers close
                    "80", "85", "90", "95", "100",       // recovery
                ),
            )

        val ast =
            strategy("close_on_dd", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val fast by letting(ema(btc.close, period = 3))
                val slow by letting(ema(btc.close, period = 7))
                rule {
                    whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever((position(btc) gt 0.bd) and (Account.totalPnl lt (-10).bd))
                    then { closeStream(btc) }
                }
            }

        val strat = AstCompiler().compile(ast)
        val result =
            Backtest(
                strategies = listOf("close_on_dd" to strat),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        // We expect at least one Buy and one Sell from the close
        val sides = result.trades.map { it.trade.side }
        assertThat(sides).contains(com.qkt.common.Side.BUY)
        assertThat(sides).contains(com.qkt.common.Side.SELL)
        // Final position should be flat or near it (the close fired)
        assertThat(result.trades).hasSizeGreaterThanOrEqualTo(2)
    }
}
```

> **Note:** the assertion is correctness-of-behavior, not a tight equivalence to a reference. The Log action is exercised implicitly via the surface; logger output isn't captured (asserting on log lines is fragile). If you want stronger guarantees, build a custom logger appender — out of scope for 11c3.

- [ ] **Step 2: Run + commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CloseEndToEndTest'
git add src/test/kotlin/com/qkt/dsl/compile/CloseEndToEndTest.kt
git commit -m "test(dsl): end-to-end CLOSE on drawdown"
```

---

### Task 8: Build green + ktlint

- [ ] `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew ktlintFormat`. If anything reformatted, commit `style: ktlint format 11c3 sources`.
- [ ] Re-run `./gradlew build`.

---

### Task 9: Phase changelog

**Files:**
- Create: `docs/phases/phase-11c3-actions.md`

Cover: summary, what's new (Log + CLOSE + CLOSE_ALL, action callable signature change, deferred CANCEL/CANCEL_ALL), migration (action signature ripple is internal), usage cookbook (drawdown-close pattern, multi-position close-all pattern, log-on-event pattern), testing patterns (the `CloseEndToEndTest` shape; how to capture logger output if needed), known limitations (CANCEL deferred; CLOSE on flat is no-op; CLOSE_ALL only sees positions held by this strategy), references.

- [ ] **Step 1: Commit**

```bash
git add docs/phases/phase-11c3-actions.md
git commit -m "docs: phase 11c3 changelog"
```

---

### Task 10: Pre-push checklist

Per qkt skill §4. Then hand off to `superpowers:finishing-a-development-branch`. Merge with `merge: phase 11c3 actions`. Fill in the merge SHA on `main` as a `docs:` commit afterward.
