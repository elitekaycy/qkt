# Phase 13b — STACK Polish + CANCEL + PORTFOLIO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three sub-phases in one merge — α STACK polish (close 13a test gaps + ChildRr in stack outerBracket), β CANCEL (enable `CANCEL <stream>` and `CANCEL_ALL` DSL actions), γ PORTFOLIO (regime-switched composition of N strategies via `IMPORT` and `WHEN ... RUN`).

**Architecture:**
- α: small fixes to OrderManager (`computeChildPrice` ChildRr branch + reorder `attachLayerSl`/`attachLayerTp` to share SL distance) and three new backtest scenarios.
- β: rename `Signal.CancelStacksForSymbol` → `Signal.CancelPendingForSymbol`; generalize `OrderManager.cancelStacksForSymbol` → `cancelPendingForSymbol` to cover non-stack pending too; replace `error("deferred")` arms in `ActionCompiler`.
- γ: new AST (`PortfolioAst`, `ImportClause`, `PortfolioRule`); new lexer/parser surface; `PortfolioLoader` (file resolution + cycle detection); `PortfolioStrategy` (gate eval + transition diff + dispatch + deactivation cleanup); CLI sub-key syntax (`portfolio:child`); Kotlin DSL parity.

**Tech Stack:** Kotlin 1.9, JDK 21, Gradle, JUnit 5, AssertJ, ktlint.

**Spec:** `docs/superpowers/specs/2026-05-09-trading-engine-phase13b-design.md`.

---

## File map

### Sub-phase α (STACK polish)

| Path | Action | Reason |
|---|---|---|
| `src/main/kotlin/com/qkt/app/OrderManager.kt` | Modify | `computeChildPrice` adds ChildRr branch; `onStackLayerFilled` reorders to capture SL distance for TP RR computation |
| `src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt` | Modify | Add 3 scenarios (SELL pyramid, BUY BELOW, concurrent stacks) |
| `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt` | Modify | Add ChildRr test |
| `docs/phases/phase-13a-stack.md` | Modify | Remove ChildRr from limitations |

### Sub-phase β (CANCEL)

| Path | Action | Reason |
|---|---|---|
| `src/main/kotlin/com/qkt/strategy/Signal.kt` | Modify | Rename `CancelStacksForSymbol` → `CancelPendingForSymbol` |
| `src/main/kotlin/com/qkt/app/OrderManager.kt` | Modify | Rename + generalize `cancelStacksForSymbol` → `cancelPendingForSymbol` |
| `src/main/kotlin/com/qkt/app/TradingPipeline.kt` | Modify | Update signal handler |
| `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` | Modify | Replace deferred-error arms with `compileCancel` / `compileCancelAll` |
| `src/main/kotlin/com/qkt/cli/RunCommand.kt` | Modify | Update signalToJson |
| `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` | Modify | Update signalToJson |
| `src/main/kotlin/com/qkt/execution/OrderFactory.kt` | Modify | Update sealed when |
| `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt` | Modify | Add `cancelStream` / `cancelAll` builders if missing |
| `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt` | Modify | Add CANCEL round-trip cases |
| Numerous test files | Modify | Update `Signal.CancelStacksForSymbol` references to new name |

### Sub-phase γ (PORTFOLIO) — created

| Path | Reason |
|---|---|
| `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt` | `PortfolioAst`, `ImportClause`, `PortfolioRule` (sealed), `WhenRun`, `AlwaysRun` |
| `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt` | File resolution, child compilation, cycle detection |
| `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioCompiled.kt` | Result of loader: AST + compiled children |
| `src/main/kotlin/com/qkt/app/PortfolioStrategy.kt` | `Strategy` impl: gate eval, transition diff, dispatch |
| `src/main/kotlin/com/qkt/dsl/kotlin/Portfolio.kt` | Kotlin DSL builders |
| `src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt` | PORTFOLIO/IMPORT/AS/RUN/HOLD lex tests |
| `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt` | Parser tests |
| `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioLoaderTest.kt` | File loading + cycle detection |
| `src/test/kotlin/com/qkt/app/PortfolioStrategyTest.kt` | Gate eval + transitions + cleanup |
| `src/test/kotlin/com/qkt/dsl/kotlin/PortfolioBuilderTest.kt` | Kotlin DSL parity |
| `src/test/kotlin/com/qkt/backtest/PortfolioBacktestTest.kt` | End-to-end backtest scenarios |
| `src/test/resources/dsl/portfolio_simple.qkt` | Fixture |
| `src/test/resources/dsl/portfolio_cycle_a.qkt` | Cycle fixture |
| `src/test/resources/dsl/portfolio_cycle_b.qkt` | Cycle fixture |

### Sub-phase γ (PORTFOLIO) — modified

| Path | Action |
|---|---|
| `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` | Add PORTFOLIO, IMPORT, AS, RUN, HOLD |
| `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` | Add `parseFile()` dispatch + `parsePortfolio` |
| `src/main/kotlin/com/qkt/dsl/parse/Dsl.kt` | Add file-root sealed type or extend existing |
| `src/main/kotlin/com/qkt/cli/ParseCommand.kt` | Recognize portfolio files |
| `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` | Dispatch portfolio backtests |
| `src/main/kotlin/com/qkt/cli/RunCommand.kt` | Dispatch portfolio live runs |
| `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` | Track portfolio metadata |
| `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt` | Sub-key syntax parsing |
| `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt` | Sub-key resolution server-side |
| `src/main/kotlin/com/qkt/strategy/StrategyContext.kt` | Add `scopedTo(strategyId)` helper |
| `docs/phases/phase-13b.md` | Phase changelog |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | Bump VERSION to 0.15.0 |

---

## Branching and commit cadence

- Working branch: `phase13b-portfolio` (already cut from `main`, contains spec commit).
- Each task ends with one commit. Subject only, Conventional Commits format. No body, no AI footer.
- Run `./gradlew ktlintCheck` before each commit.
- Run targeted tests per task; full `./gradlew build` before finishing-a-development-branch.

---

# Sub-phase α — STACK polish

## Task 1: ChildRr support in stack outerBracket

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`

ChildRr (`TAKE PROFIT RR 2.0`) means TP distance = SL distance × multiplier. The current code throws because `attachLayerTp` is called before SL distance is captured. Reorder so SL distance is computed first, then TP can use it.

- [ ] **Step 1: Write failing test**

Append to `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`:

```kotlin
    @Test
    fun `ChildRr in stack outerBracket computes TP at SL distance times multiplier`() {
        val plan =
            StackPlan(
                layers = listOf(
                    LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate),
                ),
                outerBracket = com.qkt.dsl.ast.BracketAst(
                    stopLoss = com.qkt.dsl.ast.ChildBy(NumLit(BigDecimal("50"))),
                    takeProfit = com.qkt.dsl.ast.ChildRr(NumLit(BigDecimal("2.0"))),
                ),
            )
        val req = OrderRequest.Stack(
            id = "stk-rr",
            symbol = "BTCUSDT",
            side = Side.BUY,
            quantity = BigDecimal("0.1"),
            plan = plan,
            timeInForce = TimeInForce.GTC,
            timestamp = clock.now(),
        )
        manager.submit(req)
        bus.publish(BrokerEvent.OrderFilled(
            clientOrderId = "${req.id}-l1",
            brokerOrderId = "b1",
            symbol = "BTCUSDT",
            side = Side.BUY,
            price = BigDecimal("50000"),
            quantity = BigDecimal("0.1"),
            timestamp = clock.now(),
        ))
        // SL distance = 50, RR multiplier = 2.0, so TP distance = 100. TP price = 50100.
        val active = manager.activeOrders()
        val sl = active.first { it.id == "${req.id}-l1-sl" }.request as OrderRequest.Stop
        val tp = active.first { it.id == "${req.id}-l1-tp" }.request as OrderRequest.Limit
        assertThat(sl.stopPrice).isEqualByComparingTo(BigDecimal("49950"))
        assertThat(tp.limitPrice).isEqualByComparingTo(BigDecimal("50100"))
    }
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: FAIL with "ChildRr is not supported in STACK outerBracket".

- [ ] **Step 3: Refactor OrderManager**

In `src/main/kotlin/com/qkt/app/OrderManager.kt`, modify `computeChildPrice` to accept an optional `slDistance: BigDecimal? = null` parameter and add a ChildRr branch:

```kotlin
    private fun computeChildPrice(
        childPrice: com.qkt.dsl.ast.ChildPriceAst,
        side: Side,
        fillPrice: BigDecimal,
        isStopLoss: Boolean,
        slDistance: BigDecimal? = null,
    ): BigDecimal {
        return when (childPrice) {
            is com.qkt.dsl.ast.ChildBy -> {
                val distance = (childPrice.distance as? com.qkt.dsl.ast.NumLit)?.value
                    ?: error("STACK outerBracket child distance must be a literal in v1")
                applySign(fillPrice, distance, side, isStopLoss)
            }
            is com.qkt.dsl.ast.ChildAt -> {
                (childPrice.price as? com.qkt.dsl.ast.NumLit)?.value
                    ?: error("STACK outerBracket ChildAt price must be a literal in v1")
            }
            is com.qkt.dsl.ast.ChildPct -> {
                val frac = (childPrice.frac as? com.qkt.dsl.ast.NumLit)?.value
                    ?: error("STACK outerBracket ChildPct must be a literal in v1")
                val distance = fillPrice.multiply(frac, com.qkt.common.Money.CONTEXT)
                applySign(fillPrice, distance, side, isStopLoss)
            }
            is com.qkt.dsl.ast.ChildRr -> {
                require(!isStopLoss) { "RR cannot be used for STOP LOSS — only TAKE PROFIT" }
                val multiplier = (childPrice.multiplier as? com.qkt.dsl.ast.NumLit)?.value
                    ?: error("STACK outerBracket ChildRr multiplier must be a literal in v1")
                val sl = slDistance ?: error("ChildRr requires a resolvable STOP LOSS distance")
                val distance = sl.multiply(multiplier, com.qkt.common.Money.CONTEXT)
                applySign(fillPrice, distance, side, isStopLoss = false)
            }
        }
    }

    private fun applySign(
        fillPrice: BigDecimal,
        distance: BigDecimal,
        side: Side,
        isStopLoss: Boolean,
    ): BigDecimal {
        val sign =
            if (side == Side.BUY) {
                if (isStopLoss) BigDecimal("-1") else BigDecimal("1")
            } else {
                if (isStopLoss) BigDecimal("1") else BigDecimal("-1")
            }
        return (fillPrice + distance.multiply(sign)).setScale(com.qkt.common.Money.SCALE, com.qkt.common.Money.ROUNDING)
    }
```

Modify `attachLayerSl` to return the SL distance (for sharing with TP):

```kotlin
    private fun attachLayerSl(
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
    ): BigDecimal? {
        val state = stacks.get(stackId) ?: return null
        val stopLoss = state.outerBracket?.stopLoss ?: return null
        val parent = (orders[stackId]?.request as? OrderRequest.Stack) ?: return null
        val slPrice = computeChildPrice(stopLoss, parent.side, fillPrice, isStopLoss = true)
        // ...rest as before, returning slDistance at the end
        val slOrderId = "$layerOrderId-sl"
        val exitSide = if (parent.side == Side.BUY) Side.SELL else Side.BUY
        val slReq = OrderRequest.Stop(
            id = slOrderId,
            symbol = parent.symbol,
            side = exitSide,
            quantity = orders[layerOrderId]?.request?.quantity ?: return null,
            stopPrice = slPrice,
            timeInForce = parent.timeInForce,
            timestamp = clock.now(),
            strategyId = parent.strategyId,
        )
        track(/* ManagedOrder for SL */)
        update(layerOrderId) { it.copy(childClientOrderIds = it.childClientOrderIds + slOrderId) }
        dispatch(slReq)
        return (fillPrice - slPrice).abs()
    }
```

Modify `attachLayerTp` to accept the SL distance and pass it through:

```kotlin
    private fun attachLayerTp(
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
        slDistance: BigDecimal?,
    ): String? {
        val state = stacks.get(stackId) ?: return null
        val takeProfit = state.outerBracket?.takeProfit ?: return null
        val parent = (orders[stackId]?.request as? OrderRequest.Stack) ?: return null
        val tpPrice = computeChildPrice(takeProfit, parent.side, fillPrice, isStopLoss = false, slDistance = slDistance)
        // ...rest as before, return tpOrderId
        val tpOrderId = "$layerOrderId-tp"
        val exitSide = if (parent.side == Side.BUY) Side.SELL else Side.BUY
        val tpReq = OrderRequest.Limit(
            id = tpOrderId,
            symbol = parent.symbol,
            side = exitSide,
            quantity = orders[layerOrderId]?.request?.quantity ?: return null,
            limitPrice = tpPrice,
            timeInForce = parent.timeInForce,
            timestamp = clock.now(),
            strategyId = parent.strategyId,
        )
        track(/* ManagedOrder for TP */)
        update(layerOrderId) { it.copy(childClientOrderIds = it.childClientOrderIds + tpOrderId) }
        dispatch(tpReq)
        return tpOrderId
    }
```

Update `onStackLayerFilled` to pass the SL distance to `attachLayerTp`:

```kotlin
    private fun onStackLayerFilled(e: BrokerEvent.OrderFilled) {
        val owner = stacks.markFilled(e.clientOrderId) ?: return
        val state = stacks.get(owner) ?: return
        if (state.layerOneOrderId == e.clientOrderId && state.anchor == null) {
            stacks.setAnchor(owner, e.price, clock.now())
            materializePendingLayers(owner, anchor = e.price)
        }
        val slDistance = attachLayerSl(owner, e.clientOrderId, e.price)
        val tpId = attachLayerTp(owner, e.clientOrderId, e.price, slDistance)
        if (slDistance != null && tpId != null) {
            val slId = "${e.clientOrderId}-sl"
            siblings[slId] = listOf(tpId)
            siblings[tpId] = listOf(slId)
        }
    }
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS (existing 12 tests + 1 new = 13).

Full suite: `./gradlew test -q`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(app): support ChildRr in STACK outerBracket take-profit"
```

---

## Task 2: Backtest scenarios — SELL pyramid, BUY BELOW, concurrent stacks

**Files:**
- Modify: `src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt`

- [ ] **Step 1: Read existing scenarios**

Open `src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt` to learn the harness style — three scenarios already exist (BUY happy path, BUY SL early stop, WITHIN expiry).

- [ ] **Step 2: Add SELL pyramid scenario**

```kotlin
    @Test
    fun `SELL stack pyramid fills three layers at decreasing prices`() {
        // Strategy: SELL btc with STACK 3 SPACING 100 (TRADE_DIRECTION = below entry).
        // Tick stream: 50500 → 50000 (layer 1) → 49900 → 49800.
        val strategy = newSellStackStrategy(spacing = BigDecimal("100"))
        val backtest = newBacktest(strategy)
        backtest.feed(tick("BTCUSDT", BigDecimal("50500"), 0L))
        backtest.feed(tick("BTCUSDT", BigDecimal("50000"), 60_000L))
        backtest.feed(tick("BTCUSDT", BigDecimal("49900"), 120_000L))
        backtest.feed(tick("BTCUSDT", BigDecimal("49800"), 180_000L))
        backtest.flush()
        val sellFills = backtest.tradeLog.filter { it.symbol == "BTCUSDT" && it.side == Side.SELL }
        assertThat(sellFills.map { it.price }).containsExactly(
            BigDecimal("50000"),
            BigDecimal("49900"),
            BigDecimal("49800"),
        )
    }
```

(`newSellStackStrategy` is a helper to build — mirror the existing `newBuyStackStrategy` if present, else inline the strategy construction.)

- [ ] **Step 3: Add BUY BELOW scenario**

```kotlin
    @Test
    fun `BUY stack with BELOW direction averages down at decreasing prices`() {
        val strategy = newBuyStackBelowStrategy(spacing = BigDecimal("100"))
        val backtest = newBacktest(strategy)
        // Tick stream: 49500 → 50000 (layer 1) → 49900 → 49800.
        backtest.feed(tick("BTCUSDT", BigDecimal("49500"), 0L))
        backtest.feed(tick("BTCUSDT", BigDecimal("50000"), 60_000L))
        backtest.feed(tick("BTCUSDT", BigDecimal("49900"), 120_000L))
        backtest.feed(tick("BTCUSDT", BigDecimal("49800"), 180_000L))
        backtest.flush()
        val buyFills = backtest.tradeLog.filter { it.symbol == "BTCUSDT" && it.side == Side.BUY }
        assertThat(buyFills.map { it.price }).containsExactly(
            BigDecimal("50000"),
            BigDecimal("49900"),
            BigDecimal("49800"),
        )
    }
```

- [ ] **Step 4: Add concurrent-stacks scenario**

```kotlin
    @Test
    fun `concurrent stacks one tp does not cancel other pending layers`() {
        // Two separate stack rules in the same strategy. Each fires its own STACK.
        val strategy = newConcurrentStacksStrategy()
        val backtest = newBacktest(strategy)
        // Tick stream that fires both stacks then triggers TP on the first stack only.
        backtest.feed(tick("BTCUSDT", BigDecimal("50000"), 0L))   // l1 of stack A
        backtest.feed(tick("BTCUSDT", BigDecimal("50100"), 60_000L)) // l1 of stack B + l2 of stack A
        backtest.feed(tick("BTCUSDT", BigDecimal("50500"), 120_000L)) // stack A's TP fires (assume +500 TP)
        backtest.flush()
        // Stack B's pending layers should still be present (not cancelled by stack A's TP).
        val bPendingLayers = backtest.orderManager.activeOrders().filter { it.id.startsWith("stkB-l") && it.state == OrderState.PENDING }
        assertThat(bPendingLayers).isNotEmpty
    }
```

- [ ] **Step 5: Run — expect pass**

Run: `./gradlew test --tests com.qkt.backtest.StackBacktestTest -q`
Expected: PASS (3 existing + 3 new = 6).

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt
git commit -m "test(backtest): SELL pyramid BELOW direction and concurrent stack scenarios"
```

---

## Task 3: Update changelog known-limitations

**Files:**
- Modify: `docs/phases/phase-13a-stack.md`

- [ ] **Step 1: Remove ChildRr from limitations**

Find the line in Known Limitations that says "ChildRr in stack outerBracket throws (risk-metrics plumbing not yet wired)". Remove it.

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-13a-stack.md
git commit -m "docs: phase 13a remove ChildRr limitation now that it is supported"
```

---

# Sub-phase β — CANCEL action

## Task 4: Rename `Signal.CancelStacksForSymbol` → `CancelPendingForSymbol`

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/Signal.kt`
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (rename method, generalize behavior)
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` (CLOSE / CLOSE_ALL emission sites)
- Modify: `src/main/kotlin/com/qkt/cli/RunCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Modify: `src/main/kotlin/com/qkt/execution/OrderFactory.kt`
- Modify: any tests referencing the old name

- [ ] **Step 1: Rename in Signal.kt**

```kotlin
sealed class Signal {
    data class Buy(val symbol: String, val size: BigDecimal) : Signal()
    data class Sell(val symbol: String, val size: BigDecimal) : Signal()
    data class Submit(val request: OrderRequest) : Signal()
    data class CancelPendingForSymbol(val symbol: String) : Signal()
}
```

- [ ] **Step 2: Update OrderManager method**

Rename `cancelStacksForSymbol(symbol)` to `cancelPendingForSymbol(symbol)`. Generalize the body to also cancel non-stack pending orders for the symbol:

```kotlin
fun cancelPendingForSymbol(symbol: String) {
    // Cancel pending stacks targeting this symbol.
    val stackIds = stacks.all().filter { state ->
        (orders[state.id]?.request as? OrderRequest.Stack)?.symbol == symbol
    }.map { it.id }
    for (id in stackIds) cancel(id)
    // Cancel any other pending orders for the symbol.
    val pending = orders.values
        .filter { it.state == OrderState.PENDING && it.request.symbol == symbol }
        .map { it.id }
    for (id in pending) cancel(id)
}
```

- [ ] **Step 3: Update all call sites**

Compiler will surface every reference to the old name. Update:
- `OrderFactory.toOrderRequest`: `is Signal.CancelPendingForSymbol -> null`.
- `TradingPipeline.kt`: `if (sig is Signal.CancelPendingForSymbol) { orderManager.cancelPendingForSymbol(sig.symbol); return@emit }`.
- `RunCommand.kt`/`StrategyHandle.kt` `signalToJson`: rename branch + JSON `kind` value (e.g., `"cancel_pending"`).
- `ActionCompiler.kt` CLOSE / CLOSE_ALL emission sites: rename `Signal.CancelStacksForSymbol` → `Signal.CancelPendingForSymbol`.
- Any test files (`ActionCompilerExtensionsTest`, etc.): rename references.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test -q`
Expected: BUILD SUCCESSFUL. (No new test added in this task — purely refactor.)

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add -A  # all the rename touches; verify with git status first
git commit -m "refactor(strategy): rename CancelStacksForSymbol to CancelPendingForSymbol"
```

---

## Task 5: Implement `compileCancel` and `compileCancelAll` in ActionCompiler

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Create or modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerCancelTest.kt`

- [ ] **Step 1: Write failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerCancelTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionCompilerCancelTest {
    private fun makeCtx(): EvalContext {
        val candle = Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
        return EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    }

    @Test
    fun `CANCEL emits CancelPendingForSymbol for the stream`() {
        val signals = ActionCompiler(ExprCompiler()).compile(Cancel("btc")).invoke(makeCtx())
        assertThat(signals).containsExactly(Signal.CancelPendingForSymbol("BTCUSDT"))
    }

    @Test
    fun `CANCEL_ALL emits one CancelPendingForSymbol per known stream`() {
        val ctx = EvalContext(
            candle = Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L),
            streams = mapOf(
                "btc" to HubKey("BACKTEST", "BTCUSDT", "1m"),
                "eth" to HubKey("BACKTEST", "ETHUSDT", "1m"),
            ),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
        val signals = ActionCompiler(ExprCompiler()).compile(CancelAll).invoke(ctx)
        assertThat(signals).containsExactlyInAnyOrder(
            Signal.CancelPendingForSymbol("BTCUSDT"),
            Signal.CancelPendingForSymbol("ETHUSDT"),
        )
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.compile.ActionCompilerCancelTest -q`
Expected: FAIL with the existing `error("CANCEL action is deferred...")`.

- [ ] **Step 3: Replace deferred-error arms**

In `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`, replace:

```kotlin
            is Cancel ->
                error("CANCEL action is deferred — engine cancellation API needs broker-side surface; revisit alongside Phase 11d order lifecycle work")
            is CancelAll ->
                error("CANCEL_ALL action is deferred — engine cancellation API needs broker-side surface; revisit alongside Phase 11d order lifecycle work")
```

with:

```kotlin
            is Cancel -> compileCancel(action.stream)
            is CancelAll -> compileCancelAll()
```

Add the methods:

```kotlin
    private fun compileCancel(streamAlias: String): (EvalContext) -> List<Signal> =
        { ctx ->
            val symbol = ctx.streams[streamAlias]?.symbol ?: error("Unknown stream alias: $streamAlias")
            listOf(Signal.CancelPendingForSymbol(symbol))
        }

    private fun compileCancelAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            ctx.streams.values.mapNotNull { it.symbol }.distinct().map { Signal.CancelPendingForSymbol(it) }
        }
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.compile.ActionCompilerCancelTest -q`
Expected: PASS (2 tests).

Full suite: `./gradlew test -q`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerCancelTest.kt
git commit -m "feat(dsl): enable CANCEL and CANCEL_ALL actions"
```

---

## Task 6: Round-trip equivalence for CANCEL / CANCEL_ALL

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt` (if `cancelStream`/`cancelAll` builders missing)

- [ ] **Step 1: Verify ActionScope has the builders**

Check `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`. The Kotlin DSL likely already has `cancelStream(stream)` and `cancelAll()` returning the corresponding ActionAst. If not, add:

```kotlin
    fun cancelStream(stream: StreamRef): ActionAst = Cancel(stream.alias)
    fun cancelAll(): ActionAst = CancelAll
```

- [ ] **Step 2: Add round-trip test cases**

Append to `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt`:

```kotlin
    @Test
    fun `CANCEL round trips`() {
        val text = """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 100
                THEN CANCEL btc
        """.trimIndent()
        val parsed = (Parser(Lexer(text).tokenize()).parseStrategy() as ParseResult.Success).value
        val handwritten = strategy("t") {
            symbols { stream("btc", "BACKTEST", "BTCUSDT", "1m") }
            rules {
                whenThen(
                    cond = streamFieldRef("btc", "close") gt num("100"),
                    action = ActionScope.cancelStream(StreamRef("btc")),
                )
            }
        }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `CANCEL_ALL round trips`() {
        val text = """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close < 50
                THEN CANCEL_ALL
        """.trimIndent()
        val parsed = (Parser(Lexer(text).tokenize()).parseStrategy() as ParseResult.Success).value
        val handwritten = strategy("t") {
            symbols { stream("btc", "BACKTEST", "BTCUSDT", "1m") }
            rules {
                whenThen(
                    cond = streamFieldRef("btc", "close") lt num("50"),
                    action = ActionScope.cancelAll(),
                )
            }
        }
        assertThat(parsed).isEqualTo(handwritten)
    }
```

- [ ] **Step 3: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.RoundTripEquivalenceTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt
git commit -m "test(dsl): round-trip CANCEL and CANCEL_ALL"
```

---

# Sub-phase γ — PORTFOLIO

## Task 7: PORTFOLIO AST

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.qkt.dsl.ast

data class PortfolioAst(
    val name: String,
    val version: Int,
    val symbols: SymbolsBlock?,
    val imports: List<ImportClause>,
    val rules: List<PortfolioRule>,
) {
    init {
        require(imports.isNotEmpty()) { "PORTFOLIO must have at least one IMPORT" }
        require(imports.map { it.alias }.distinct().size == imports.size) {
            "PORTFOLIO aliases must be unique"
        }
        require(imports.map { it.path }.distinct().size == imports.size) {
            "PORTFOLIO import paths must be unique (no overrides in v1)"
        }
        val knownAliases = imports.map { it.alias }.toSet()
        for (rule in rules) {
            val refAlias = when (rule) {
                is WhenRun -> rule.alias
                is AlwaysRun -> rule.alias
            }
            require(refAlias in knownAliases) {
                "PORTFOLIO rule references unknown alias '$refAlias'"
            }
        }
    }
}

data class ImportClause(
    val path: String,
    val alias: String,
    val hold: Boolean = false,
)

sealed interface PortfolioRule

data class WhenRun(
    val cond: ExprAst,
    val alias: String,
) : PortfolioRule

data class AlwaysRun(
    val alias: String,
) : PortfolioRule
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt
git commit -m "feat(dsl): add PortfolioAst"
```

---

## Task 8: Lexer — PORTFOLIO / IMPORT / AS / RUN / HOLD tokens

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerPortfolioTest {
    @Test
    fun `PORTFOLIO IMPORT AS RUN HOLD lex as keyword tokens`() {
        val tokens = Lexer("PORTFOLIO IMPORT AS RUN HOLD").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.PORTFOLIO,
            TokenKind.IMPORT,
            TokenKind.AS,
            TokenKind.RUN,
            TokenKind.HOLD,
            TokenKind.EOF,
        )
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.LexerPortfolioTest -q`
Expected: FAIL with "Unresolved reference: PORTFOLIO".

- [ ] **Step 3: Add tokens**

In `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`, add the new entries below the existing CANCEL_ALL/STACK/etc. block:

```kotlin
    PORTFOLIO,
    IMPORT,
    AS,
    RUN,
    HOLD,
```

(Auto-included in the lexer's `KEYWORDS` map via the existing filter.)

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.LexerPortfolioTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt
git commit -m "feat(dsl): add PORTFOLIO IMPORT AS RUN HOLD tokens"
```

---

## Task 9: Parser file root + `parseFile()` dispatch

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Dsl.kt` (or add a sealed root type)
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`

- [ ] **Step 1: Add the root sealed type**

In `src/main/kotlin/com/qkt/dsl/parse/Dsl.kt` (or a new small file), add:

```kotlin
sealed interface ParsedFile

data class StrategyFile(val ast: com.qkt.dsl.ast.StrategyAst) : ParsedFile
data class PortfolioFile(val ast: com.qkt.dsl.ast.PortfolioAst) : ParsedFile
```

- [ ] **Step 2: Add `parseFile()` to Parser**

In `Parser.kt`:

```kotlin
fun parseFile(): ParseResult<ParsedFile> {
    return when (peek().kind) {
        TokenKind.STRATEGY -> parseStrategy().map { StrategyFile(it) }
        TokenKind.PORTFOLIO -> parsePortfolio().map { PortfolioFile(it) }
        else -> ParseResult.Failure(listOf(ParseError("expected STRATEGY or PORTFOLIO at file start, got '${peek().lexeme}'", peek().line, peek().col)))
    }
}
```

`parsePortfolio()` is added in Task 10. For now stub it to throw:

```kotlin
internal fun parsePortfolio(): ParseResult<PortfolioAst> = error("parsePortfolio: implemented in Task 10")
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/Dsl.kt src/main/kotlin/com/qkt/dsl/parse/Parser.kt
git commit -m "feat(dsl): add ParsedFile root sealed type"
```

---

## Task 10: Parser — `parsePortfolio` + `parseImport` + `parsePortfolioRule`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserPortfolioTest {
    private fun parsePortfolioText(src: String): PortfolioAst {
        val tokens = Lexer(src).tokenize()
        val result = Parser(tokens).parseFile()
        val success = result as ParseResult.Success<ParsedFile>
        return (success.value as PortfolioFile).ast
    }

    @Test
    fun `simple PORTFOLIO with single IMPORT and bare RUN parses`() {
        val ast = parsePortfolioText(
            """
            PORTFOLIO p1 VERSION 1
            IMPORT 'a.qkt' AS a
            RULES
                RUN a
            """.trimIndent()
        )
        assertThat(ast.name).isEqualTo("p1")
        assertThat(ast.version).isEqualTo(1)
        assertThat(ast.imports).containsExactly(ImportClause(path = "a.qkt", alias = "a", hold = false))
        assertThat(ast.rules).containsExactly(AlwaysRun("a"))
    }

    @Test
    fun `PORTFOLIO with two imports and WHEN-RUN parses`() {
        val ast = parsePortfolioText(
            """
            PORTFOLIO p2 VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1h
            IMPORT 'trend.qkt' AS trend
            IMPORT 'range.qkt' AS range HOLD
            RULES
                WHEN btc.close > 100 RUN trend
                WHEN btc.close <= 100 RUN range
            """.trimIndent()
        )
        assertThat(ast.imports).hasSize(2)
        assertThat(ast.imports[1].hold).isTrue
        assertThat(ast.rules).hasSize(2)
        assertThat(ast.rules[0]).isInstanceOf(WhenRun::class.java)
        assertThat((ast.rules[0] as WhenRun).alias).isEqualTo("trend")
    }

    @Test
    fun `PORTFOLIO duplicate alias rejected`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            parsePortfolioText(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS x
                IMPORT 'b.qkt' AS x
                """.trimIndent()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("aliases must be unique")
    }

    @Test
    fun `PORTFOLIO unknown alias in RUN rejected`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            parsePortfolioText(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS x
                RULES
                    RUN y
                """.trimIndent()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown alias")
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserPortfolioTest -q`
Expected: FAIL — `parsePortfolio` is a stub.

- [ ] **Step 3: Implement parser methods**

Replace the stub `parsePortfolio` in `Parser.kt`:

```kotlin
internal fun parsePortfolio(): ParseResult<PortfolioAst> {
    val errors = mutableListOf<ParseError>()
    return try {
        expect(TokenKind.PORTFOLIO, "expected PORTFOLIO")
        val name = expect(TokenKind.IDENT, "expected portfolio name").lexeme
        expect(TokenKind.VERSION, "expected VERSION")
        val versionTok = expect(TokenKind.NUMBER, "expected version number")
        val version = versionTok.lexeme.toIntOrNull()
            ?: error("version must be integer, got '${versionTok.lexeme}'")

        val symbols = if (peek().kind == TokenKind.SYMBOLS) parseSymbolsBlock() else null

        val imports = mutableListOf<ImportClause>()
        while (peek().kind == TokenKind.IMPORT) {
            imports.add(parseImport())
        }

        val rules = mutableListOf<PortfolioRule>()
        if (peek().kind == TokenKind.RULES) {
            advance()
            while (peek().kind == TokenKind.WHEN || peek().kind == TokenKind.RUN) {
                rules.add(parsePortfolioRule())
            }
        }

        ParseResult.Success(PortfolioAst(name, version, symbols, imports, rules))
    } catch (e: IllegalStateException) {
        ParseResult.Failure(listOf(ParseError(e.message ?: "parse failed", peek().line, peek().col)))
    }
}

internal fun parseImport(): ImportClause {
    expect(TokenKind.IMPORT, "expected IMPORT")
    val pathTok = expect(TokenKind.STRING, "expected import path string")
    expect(TokenKind.AS, "expected AS after import path")
    val aliasTok = expect(TokenKind.IDENT, "expected alias")
    val hold = if (peek().kind == TokenKind.HOLD) {
        advance()
        true
    } else {
        false
    }
    return ImportClause(path = pathTok.lexeme, alias = aliasTok.lexeme, hold = hold)
}

internal fun parsePortfolioRule(): PortfolioRule {
    return when (peek().kind) {
        TokenKind.WHEN -> {
            advance()
            val cond = parseExpr()
            expect(TokenKind.RUN, "expected RUN after WHEN expression")
            val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
            WhenRun(cond, alias)
        }
        TokenKind.RUN -> {
            advance()
            val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
            AlwaysRun(alias)
        }
        else -> error("expected WHEN or RUN, got '${peek().lexeme}'")
    }
}
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserPortfolioTest -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt
git commit -m "feat(dsl): parse PORTFOLIO IMPORT and rule blocks"
```

---

## Task 11: Parser error handling — cycle detection, missing IMPORT, etc.

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt`

These error cases are mostly covered by the AST init validations (Task 7). Add tests asserting they fire:

- [ ] **Step 1: Add tests**

```kotlin
    @Test
    fun `PORTFOLIO with no imports rejected`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            parsePortfolioText("PORTFOLIO empty VERSION 1")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one IMPORT")
    }

    @Test
    fun `PORTFOLIO with duplicate import path rejected`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            parsePortfolioText(
                """
                PORTFOLIO dup VERSION 1
                IMPORT 'same.qkt' AS x
                IMPORT 'same.qkt' AS y
                """.trimIndent()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("import paths must be unique")
    }
```

(Cycle detection is at file-load time via `PortfolioLoader`, not the parser. Tests for that go with Task 13.)

- [ ] **Step 2: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserPortfolioTest -q`
Expected: PASS (6 tests).

- [ ] **Step 3: Commit**

```bash
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt
git commit -m "test(dsl): PORTFOLIO empty-imports and duplicate-path errors"
```

---

## Task 12: Kotlin DSL — `portfolio { ... }` builder

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Portfolio.kt`
- Create: `src/test/kotlin/com/qkt/dsl/kotlin/PortfolioBuilderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioBuilderTest {
    @Test
    fun `portfolio builder produces PortfolioAst`() {
        val pf = portfolio("p", version = 1) {
            import("a.qkt", alias = "a")
            import("b.qkt", alias = "b", hold = true)
            rules {
                run("a")
                whenRun(streamFieldRef("btc", "close") gt num("100"), child = "b")
            }
        }
        assertThat(pf).isInstanceOf(PortfolioAst::class.java)
        assertThat(pf.imports).containsExactly(
            ImportClause("a.qkt", "a", hold = false),
            ImportClause("b.qkt", "b", hold = true),
        )
        assertThat(pf.rules[0]).isEqualTo(AlwaysRun("a"))
        assertThat(pf.rules[1]).isInstanceOf(WhenRun::class.java)
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.kotlin.PortfolioBuilderTest -q`
Expected: FAIL — `portfolio` doesn't exist.

- [ ] **Step 3: Create the builder**

`src/main/kotlin/com/qkt/dsl/kotlin/Portfolio.kt`:

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.PortfolioRule
import com.qkt.dsl.ast.SymbolsBlock
import com.qkt.dsl.ast.WhenRun

fun portfolio(
    name: String,
    version: Int,
    init: PortfolioBuilder.() -> Unit,
): PortfolioAst {
    val b = PortfolioBuilder(name, version)
    b.init()
    return b.build()
}

class PortfolioBuilder internal constructor(
    private val name: String,
    private val version: Int,
) {
    private var symbols: SymbolsBlock? = null
    private val imports = mutableListOf<ImportClause>()
    private val rules = mutableListOf<PortfolioRule>()

    fun symbols(init: SymbolsBuilder.() -> Unit) {
        val sb = SymbolsBuilder()
        sb.init()
        symbols = sb.build()
    }

    fun import(path: String, alias: String, hold: Boolean = false) {
        imports.add(ImportClause(path, alias, hold))
    }

    fun rules(init: PortfolioRulesBuilder.() -> Unit) {
        val rb = PortfolioRulesBuilder()
        rb.init()
        rules.addAll(rb.build())
    }

    internal fun build(): PortfolioAst = PortfolioAst(name, version, symbols, imports.toList(), rules.toList())
}

class PortfolioRulesBuilder internal constructor() {
    private val rules = mutableListOf<PortfolioRule>()

    fun whenRun(cond: ExprAst, child: String) {
        rules.add(WhenRun(cond, child))
    }

    fun run(child: String) {
        rules.add(AlwaysRun(child))
    }

    internal fun build(): List<PortfolioRule> = rules.toList()
}
```

(`SymbolsBuilder` is presumably already part of the Kotlin DSL — reuse if so. Otherwise this task may need to inline a small symbols-block helper.)

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.kotlin.PortfolioBuilderTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/kotlin/Portfolio.kt src/test/kotlin/com/qkt/dsl/kotlin/PortfolioBuilderTest.kt
git commit -m "feat(dsl): add Kotlin DSL portfolio builder"
```

---

## Task 13: PortfolioLoader — file resolution + cycle detection

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt`
- Create: `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioCompiled.kt`
- Create: `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioLoaderTest.kt`
- Create: `src/test/resources/dsl/portfolio_simple.qkt`
- Create: `src/test/resources/dsl/portfolio_cycle_a.qkt`
- Create: `src/test/resources/dsl/portfolio_cycle_b.qkt`

- [ ] **Step 1: Create fixtures**

`src/test/resources/dsl/portfolio_simple.qkt`:

```
PORTFOLIO simple VERSION 1
IMPORT 'simple_child_strategy.qkt' AS child
RULES
    RUN child
```

`src/test/resources/dsl/simple_child_strategy.qkt`:

```
STRATEGY child VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 0.1
```

`src/test/resources/dsl/portfolio_cycle_a.qkt`:

```
PORTFOLIO cyc_a VERSION 1
IMPORT 'portfolio_cycle_b.qkt' AS b
RULES
    RUN b
```

`src/test/resources/dsl/portfolio_cycle_b.qkt`:

```
PORTFOLIO cyc_b VERSION 1
IMPORT 'portfolio_cycle_a.qkt' AS a
RULES
    RUN a
```

- [ ] **Step 2: Write failing tests**

```kotlin
package com.qkt.dsl.portfolio

import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PortfolioLoaderTest {
    @Test
    fun `simple portfolio loads with one compiled child`() {
        val path = Paths.get("src/test/resources/dsl/portfolio_simple.qkt")
        val compiled = PortfolioLoader.load(path)
        assertThat(compiled.children).hasSize(1)
        assertThat(compiled.children[0].alias).isEqualTo("child")
    }

    @Test
    fun `cycle in import graph rejected`() {
        val path = Paths.get("src/test/resources/dsl/portfolio_cycle_a.qkt")
        assertThatThrownBy { PortfolioLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cycle")
    }
}
```

- [ ] **Step 3: Run — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.portfolio.PortfolioLoaderTest -q`
Expected: FAIL — class doesn't exist.

- [ ] **Step 4: Implement loader**

`src/main/kotlin/com/qkt/dsl/portfolio/PortfolioCompiled.kt`:

```kotlin
package com.qkt.dsl.portfolio

import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.compile.DslCompiledStrategy

data class PortfolioCompiled(
    val ast: PortfolioAst,
    val children: List<CompiledChild>,
)

data class CompiledChild(
    val alias: String,
    val hold: Boolean,
    val strategyId: String,
    val compiled: DslCompiledStrategy,
    val streams: List<String>,
)
```

`src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt`:

```kotlin
package com.qkt.dsl.portfolio

import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import com.qkt.dsl.parse.PortfolioFile
import com.qkt.dsl.parse.StrategyFile
import java.nio.file.Files
import java.nio.file.Path

object PortfolioLoader {
    fun load(path: Path): PortfolioCompiled {
        val visited = mutableSetOf<Path>()
        val canonical = path.toAbsolutePath().normalize()
        return loadPortfolio(canonical, visited)
    }

    private fun loadPortfolio(path: Path, visited: MutableSet<Path>): PortfolioCompiled {
        if (path in visited) error("cycle in import graph at $path")
        visited.add(path)
        val text = Files.readString(path)
        val tokens = Lexer(text).tokenize()
        val parsed = Parser(tokens).parseFile()
        val ast = when (parsed) {
            is ParseResult.Success -> when (val pf = parsed.value) {
                is PortfolioFile -> pf.ast
                is StrategyFile -> error("expected PORTFOLIO at $path, got STRATEGY")
            }
            is ParseResult.Failure -> error("parse error at $path: ${parsed.errors.joinToString()}")
        }
        val children = ast.imports.map { imp ->
            val childPath = path.parent.resolve(imp.path).toAbsolutePath().normalize()
            require(childPath !in visited) { "cycle in import graph: $childPath" }
            val childText = Files.readString(childPath)
            val childParsed = Parser(Lexer(childText).tokenize()).parseFile()
            val childAst = when (childParsed) {
                is ParseResult.Success -> when (val cf = childParsed.value) {
                    is StrategyFile -> cf.ast
                    is PortfolioFile -> error("nested PORTFOLIO not supported in v1: $childPath")
                }
                is ParseResult.Failure -> error("parse error at $childPath: ${childParsed.errors.joinToString()}")
            }
            val childCompiled = AstCompiler.compile(childAst)
            val childStrategyId = "${ast.name}:${imp.alias}"
            CompiledChild(
                alias = imp.alias,
                hold = imp.hold,
                strategyId = childStrategyId,
                compiled = childCompiled,
                streams = childAst.symbols?.streams?.map { it.alias } ?: emptyList(),
            )
        }
        visited.remove(path)
        return PortfolioCompiled(ast, children)
    }
}
```

(Adapt to actual `AstCompiler` API and `SymbolsBlock` shape.)

- [ ] **Step 5: Run — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.portfolio.PortfolioLoaderTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/portfolio/ src/test/kotlin/com/qkt/dsl/portfolio/ src/test/resources/dsl/portfolio_*.qkt src/test/resources/dsl/simple_child_strategy.qkt
git commit -m "feat(dsl): PortfolioLoader with cycle detection"
```

---

## Task 14: StrategyContext — `scopedTo(strategyId)`

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/StrategyContext.kt`

- [ ] **Step 1: Add the helper**

```kotlin
fun StrategyContext.scopedTo(
    childStrategyId: String,
    childPositions: StrategyPositionView,
    childPnl: StrategyPnLView,
    childRisk: RiskView,
): StrategyContext = copy(
    strategyId = childStrategyId,
    positions = childPositions,
    pnl = childPnl,
    risk = childRisk,
)
```

The caller (PortfolioStrategy in Task 15) provides the per-child views.

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/strategy/StrategyContext.kt
git commit -m "feat(strategy): add StrategyContext.scopedTo helper"
```

---

## Task 15: PortfolioStrategy — gate eval + dispatch + transitions

**Files:**
- Create: `src/main/kotlin/com/qkt/app/PortfolioStrategy.kt`
- Create: `src/test/kotlin/com/qkt/app/PortfolioStrategyTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioStrategyTest {
    @Test
    fun `child active when its WhenRun gate is true`() {
        // Construct PortfolioStrategy with one child gated WHEN btc.close > 100
        // Feed a tick with btc.close = 50 → child should NOT receive onTick.
        // Feed a tick with btc.close = 150 → child SHOULD receive onTick.
        // Use a recording child Strategy to count onTick invocations.
        // Specific scaffolding TBD when wiring up — adapt to PortfolioStrategy ctor.
        // ...
    }

    @Test
    fun `gate flip from true to false emits CancelPendingForSymbol for child streams`() {
        // ...
    }
}
```

(Test scaffolding adapts to whatever ctor signature PortfolioStrategy ends up with — record-style emit lambda + a hand-rolled child Strategy returning known signals.)

- [ ] **Step 2: Run — expect failure**

Class doesn't exist.

- [ ] **Step 3: Create PortfolioStrategy**

`src/main/kotlin/com/qkt/app/PortfolioStrategy.kt`:

```kotlin
package com.qkt.app

import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.Value
import com.qkt.dsl.portfolio.CompiledChild
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.WhenRun
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.scopedTo
import java.math.BigDecimal
import org.slf4j.LoggerFactory

class PortfolioStrategy(
    private val compiled: PortfolioCompiled,
    private val exprCompiler: ExprCompiler,
) : Strategy {
    private val log = LoggerFactory.getLogger(PortfolioStrategy::class.java)

    private val children: Map<String, CompiledChild> =
        compiled.children.associateBy { it.alias }

    private data class CompiledRule(
        val alias: String,
        val gate: CompiledExpr?, // null = unconditional
    )

    private val rules: List<CompiledRule> =
        compiled.ast.rules.map { rule ->
            when (rule) {
                is WhenRun -> CompiledRule(rule.alias, exprCompiler.compile(rule.cond))
                is AlwaysRun -> CompiledRule(rule.alias, null)
            }
        }

    private var lastActive: Set<String> = emptySet()

    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        val newActive = computeActiveChildren(ctx)
        applyTransitions(lastActive, newActive, ctx, emit)
        for ((alias, child) in children) {
            if (alias in newActive) {
                child.compiled.onTick(tick, ctx.scopedTo(/* per-child views */), emit)
            }
        }
        lastActive = newActive
    }

    override fun onCandle(candle: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
        // Mirror onTick but for candles. Reuse computeActiveChildren if the gate evaluates against candles.
    }

    private fun computeActiveChildren(ctx: StrategyContext): Set<String> {
        val active = mutableSetOf<String>()
        for (rule in rules) {
            val isActive = rule.gate?.let {
                val ec = buildEvalContext(ctx)
                (it.evaluate(ec) as? Value.Bool)?.v ?: false
            } ?: true
            if (isActive) active.add(rule.alias)
        }
        return active
    }

    private fun applyTransitions(
        prev: Set<String>,
        curr: Set<String>,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // Activations: nothing special (child resumes).
        for (alias in curr - prev) {
            log.info("portfolio activate portfolio={} child={}", compiled.ast.name, alias)
        }
        // Deactivations: cancel pending + (unless HOLD) close positions.
        for (alias in prev - curr) {
            val child = children[alias] ?: continue
            log.info("portfolio deactivate portfolio={} child={} hold={}", compiled.ast.name, alias, child.hold)
            for (streamAlias in child.streams) {
                val symbol = ctx.streams?.get(streamAlias)?.symbol  // may need to look up
                    ?: continue
                emit(Signal.CancelPendingForSymbol(symbol))
                if (!child.hold) {
                    val pos = child.positions(ctx).positionFor(symbol)
                    val qty = pos?.quantity ?: BigDecimal.ZERO
                    when {
                        qty.signum() > 0 -> emit(Signal.Sell(symbol, qty))
                        qty.signum() < 0 -> emit(Signal.Buy(symbol, qty.abs()))
                    }
                }
            }
        }
    }

    private fun buildEvalContext(ctx: StrategyContext): EvalContext {
        // Construct an EvalContext using the portfolio's own SYMBOLS for gate expressions.
        // This depends on the existing EvalContext shape — adapt based on actual project API.
        TODO("adapt to actual EvalContext signature")
    }
}
```

NOTE: Some helper methods (`child.positions(ctx)`, `ctx.streams`, exact EvalContext construction, `scopedTo` arguments) need to be filled in based on the actual project API. The plan author should expect this task to involve some reading and adaptation. If the existing code shape doesn't easily support this design, escalate before forcing it.

- [ ] **Step 4: Run — expect pass on the test**

Run: `./gradlew test --tests com.qkt.app.PortfolioStrategyTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/PortfolioStrategy.kt src/test/kotlin/com/qkt/app/PortfolioStrategyTest.kt
git commit -m "feat(app): PortfolioStrategy gate eval and child dispatch"
```

---

## Task 16: PortfolioStrategy — deactivation cleanup details (HOLD vs close)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/PortfolioStrategy.kt`
- Modify: `src/test/kotlin/com/qkt/app/PortfolioStrategyTest.kt`

- [ ] **Step 1: Add explicit HOLD test**

```kotlin
    @Test
    fun `HOLD child does not emit close signals on deactivation`() {
        // Build a portfolio where child is HOLD.
        // Activate the child (gate goes true → false).
        // Verify CancelPendingForSymbol IS emitted; Sell/Buy are NOT.
    }

    @Test
    fun `non-HOLD child emits close signals on deactivation matching open positions`() {
        // Same setup but without HOLD.
        // Verify CancelPendingForSymbol AND Sell are emitted.
    }
```

- [ ] **Step 2: Wire the logic**

Already in Task 15's `applyTransitions`. Verify it compiles and tests pass.

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.app.PortfolioStrategyTest -q
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/app/PortfolioStrategyTest.kt
git commit -m "test(app): PortfolioStrategy HOLD vs close deactivation"
```

---

## Task 17: Daemon registration — portfolio metadata

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Modify: relevant deploy / list paths (search `qkt deploy` flow)

- [ ] **Step 1: Add portfolio type marker to StrategyHandle**

Find the `StrategyHandle` class in 12c daemon code. Add a `type: StrategyType` field (or similar):

```kotlin
enum class StrategyType { STRATEGY, PORTFOLIO }

data class StrategyHandle(
    val id: String,
    val type: StrategyType,
    val children: List<String> = emptyList(),  // alias list for portfolios
    // ... rest of existing fields
)
```

`RealFactory` decides at deploy time whether the file is a STRATEGY or PORTFOLIO based on `parseFile()`.

- [ ] **Step 2: Update `qkt list` JSON output**

Include `type=portfolio` and `children=[...]` for portfolios.

- [ ] **Step 3: Update tests**

Existing daemon tests get a small extension: a portfolio fixture deploys, list shows it as portfolio.

- [ ] **Step 4: Run + commit**

```bash
./gradlew test --tests "com.qkt.cli.daemon.*" -q
./gradlew ktlintFormat -q
git add -A
git commit -m "feat(daemon): track portfolio type and children in StrategyHandle"
```

---

## Task 18: Daemon — sub-key syntax for status/logs/stop

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`

- [ ] **Step 1: Parse `<portfolio>:<child>` in client**

Add a helper:

```kotlin
internal fun splitSubKey(arg: String): Pair<String, String?> {
    val idx = arg.lastIndexOf(':')
    return if (idx > 0 && idx < arg.length - 1) {
        arg.substring(0, idx) to arg.substring(idx + 1)
    } else {
        arg to null
    }
}
```

Wherever the CLI calls a control endpoint for status/logs/stop with a strategy id, route through this helper.

- [ ] **Step 2: Server-side resolution**

In `ControlRoutes`, when a request includes a child suffix:
- Verify the parent is a portfolio.
- Use the child's strategyId (`<parent>:<child>`) to filter the response (PnL, positions, logs).

- [ ] **Step 3: Tests**

Add daemon tests that exercise:
- `qkt status portfolio.qkt` — top-level summary including `children=[...]`.
- `qkt status portfolio.qkt:trend` — drill-down to child.
- `qkt logs portfolio.qkt:trend` — child's log file.

- [ ] **Step 4: Run + commit**

```bash
./gradlew test --tests "com.qkt.cli.daemon.*" -q
./gradlew ktlintFormat -q
git add -A
git commit -m "feat(daemon): sub-key syntax for portfolio child observability"
```

---

## Task 19: Daemon — `qkt stop portfolio.qkt` cascades

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` (StopRoute logic)
- Modify: `src/test/kotlin/com/qkt/cli/daemon/StopRouteTest.kt`

- [ ] **Step 1: Cascade stop**

In the daemon's stop handler, when the strategy being stopped is a portfolio, call `PortfolioStrategy.onStop` (a new method) which emits deactivation cleanup for ALL children regardless of their gate state. This:
- Cancels all pending orders for all child symbols.
- Closes all open positions for non-HOLD children.
- HOLD children's positions stay open (per design).

- [ ] **Step 2: Test**

```kotlin
@Test
fun `stopping a portfolio cascades to child deactivation`() {
    // Deploy a portfolio with two children, one HOLD.
    // Stop the portfolio.
    // Verify CancelPendingForSymbol emitted for both children's symbols.
    // Verify close signals emitted only for the non-HOLD child's positions.
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.daemon.StopRouteTest -q
./gradlew ktlintFormat -q
git add -A
git commit -m "feat(daemon): qkt stop portfolio cascades to child deactivation"
```

---

## Task 20: Backtest — PortfolioStrategy works end-to-end

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` (recognize portfolio file)
- Create: `src/test/kotlin/com/qkt/backtest/PortfolioBacktestTest.kt`

- [ ] **Step 1: Wire BacktestCommand**

In `BacktestCommand.kt`, when `qkt backtest <file>` runs:
- Parse via `parseFile()`.
- If StrategyFile → existing path.
- If PortfolioFile → load via `PortfolioLoader`, wrap in `PortfolioStrategy`, drive through Backtest engine.

- [ ] **Step 2: Write 4 backtest scenarios**

```kotlin
package com.qkt.backtest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioBacktestTest {
    @Test
    fun `regime switch trend to range exclusive activation`() {
        // Build a portfolio where ADX gates trend vs range.
        // Tick stream that flips ADX above and below 25.
        // Verify only one child trades at a time.
    }

    @Test
    fun `always-on bundle two children both trade`() {
        // Both children active throughout.
        // Verify both children's signals appear in trade log.
    }

    @Test
    fun `HOLD child position survives deactivation`() {
        // HOLD child opens position then deactivates.
        // Position remains open; no close signal.
    }

    @Test
    fun `non-HOLD child position closes on deactivation`() {
        // Default (non-HOLD) child opens position then deactivates.
        // Position closes; no zombie position.
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.backtest.PortfolioBacktestTest -q
./gradlew ktlintFormat -q
git add -A
git commit -m "feat(backtest): support PORTFOLIO files end to end"
```

---

## Task 21: Round-trip equivalence for PORTFOLIO

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt`

- [ ] **Step 1: Add 2 portfolio fixtures**

```kotlin
    @Test
    fun `portfolio simple round trips`() {
        val text = """
            PORTFOLIO p VERSION 1
            IMPORT 'a.qkt' AS a
            RULES
                RUN a
        """.trimIndent()
        val parsed = (Parser(Lexer(text).tokenize()).parseFile() as ParseResult.Success).value
        val handwritten = PortfolioFile(portfolio("p", version = 1) {
            import("a.qkt", alias = "a")
            rules { run("a") }
        })
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `portfolio with WHEN-RUN round trips`() {
        // ... similar
    }
```

- [ ] **Step 2: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.RoundTripEquivalenceTest -q
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt
git commit -m "test(dsl): round-trip PORTFOLIO forms"
```

---

## Task 22: Integration — qkt deploy / list / status / stop with portfolio fixture

**Files:**
- Create: `src/test/kotlin/com/qkt/cli/daemon/PortfolioDeployIntegrationTest.kt` (or extend existing)

- [ ] **Step 1: End-to-end test**

```kotlin
@Test
fun `deploy portfolio then list shows children then stop cascades`() {
    // Spin up daemon (test harness from existing daemon tests).
    // Deploy a portfolio fixture.
    // GET /list → verify portfolio entry with children.
    // GET /status?id=portfolio → verify per-child gate state.
    // POST /stop?id=portfolio → verify children deactivate.
}
```

- [ ] **Step 2: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.daemon.PortfolioDeployIntegrationTest -q
./gradlew ktlintFormat -q
git add -A
git commit -m "test(daemon): portfolio deploy list status stop integration"
```

---

## Task 23: Phase 13b changelog

**Files:**
- Create: `docs/phases/phase-13b.md`
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt`
- Modify: `README.md`

- [ ] **Step 1: Bump version**

`BuildInfo.VERSION` → `"0.15.0"`.

- [ ] **Step 2: README update**

Add a short PORTFOLIO example in the features section.

- [ ] **Step 3: Phase changelog**

Per qkt skill §6, write `docs/phases/phase-13b.md` with all required sections:

- Summary (4 sentences covering all three sub-phases).
- What's new — bullet list of every new keyword (`PORTFOLIO`, `IMPORT`, `AS`, `RUN`, `HOLD`), AST type (`PortfolioAst`, etc.), runtime (`PortfolioStrategy`, `PortfolioLoader`, `PortfolioCompiled`, `CompiledChild`), Kotlin DSL (`portfolio` builder), CLI (sub-key `portfolio:child` syntax, deploy/list/status/stop support).
- Migration: rename `Signal.CancelStacksForSymbol` → `Signal.CancelPendingForSymbol`. Document.
- Usage cookbook: 6+ worked examples covering ChildRr, CANCEL, three PORTFOLIO shapes (regime-switched, always-on, mixed-with-HOLD), CLI sub-key drill-down.
- Testing patterns.
- Known limitations: WEIGHT, overrides, nested portfolios, hot-reload, persistence, DISABLE_ON_ERROR, portfolio-level risk caps.
- References: spec, plan, merge SHA (TBD until merge).

Aim 350-500 lines.

- [ ] **Step 4: Build verify**

Run: `./gradlew build -q`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt README.md
git commit -m "chore(cli): bump version to 0.15.0"
git add docs/phases/phase-13b.md
git commit -m "docs: phase 13b changelog"
```

---

## Task 24: Final pre-merge verification

- [ ] **Step 1: Run full pre-push checklist**

Per qkt skill §4:

```bash
./gradlew build       # all tests + ktlint pass
git status            # clean
git log --oneline main..HEAD
grep -rEn 'TODO|FIXME|XXX' src/  # no new orphan TODOs
```

- [ ] **Step 2: Document any deferred items**

If anything from the spec didn't make it (genuine surface limitations), add to the changelog's known-limitations section before merge.

- [ ] **Step 3: Hand off to finishing-a-development-branch**

Use `superpowers:finishing-a-development-branch` to merge into main.

---

## Self-review check

Reviewed against the spec on 2026-05-09:

- §2.α — Tasks 1–3 cover ChildRr support + 3 backtest scenarios + changelog cleanup.
- §2.β — Tasks 4–6 cover signal rename + ActionCompiler implementation + round-trip.
- §2.γ — Tasks 7–22 cover full PORTFOLIO surface.
- §3 worked examples — all reachable through the implemented surface (ChildRr, CANCEL, three PORTFOLIO shapes including HOLD).
- §4 architecture — Tasks 7 (AST), 8 (lexer), 9–10 (parser), 12 (Kotlin DSL), 13 (loader), 14–15 (StrategyContext + PortfolioStrategy), 17–19 (daemon), 20 (backtest).
- §5 lexer/parser — Task 8 (tokens) + Tasks 9–11 (parser).
- §7 CLI sub-key — Task 18.
- §8 failure modes — Tasks 11 (parse errors), 13 (cycle), 17–19 (daemon error paths).
- §9 testing — Tasks 1, 2, 5, 8, 10–13, 15–16, 20–22.
- §10 risk — addressed inline by tests.
- §11 phase decomposition — matches this 24-task structure.

Plan ready for execution.
