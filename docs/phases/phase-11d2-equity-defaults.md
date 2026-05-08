# Phase 11d2 — Engine Equity Surface, Percent-and-Fraction Sizing, DEFAULTS

## Summary

Phase 11d2 ships the textbook "risk 1% per trade" workflow. `StrategyPnLView` gains `equity()` and `balance()` methods backed by a per-strategy starting balance threaded through `Backtest`. The DSL exposes `ACCOUNT.equity` / `ACCOUNT.balance` accessors and three new sizing modes (`pctEquity`, `pctBalance`, `riskFrac`) that all reference the live equity. The strategy-level `DEFAULTS` block now compiles — every `BUY`/`SELL` action's `ActionOpts` is merged with the defaults at compile time, with action-level fields overriding defaults.

The headline acceptance test: `defaults { stopLoss = childBy(5); takeProfit = childRr(3) }` plus `BUY ... SIZING riskFrac(0.01)` with $10,000 equity produces a Bracket order with `quantity = 20` (= 10000 × 1% / 5 stop distance), stop-loss = entry - 5, take-profit = entry + 3 × stop_distance.

## What's new

- `StrategyPnLView.equity()` — `startingBalance + realized + unrealized`. Mark-to-market account value.
- `StrategyPnLView.balance()` — `startingBalance + realized`. Settled cash.
- `StrategyPnL.setStartingBalance(strategyId, balance)` — register a starting balance per strategy. Default is zero.
- `Backtest(... startingBalance: BigDecimal = ZERO ...)` — single starting balance applied uniformly to every strategy in the run.
- DSL `ACCOUNT.equity` and `ACCOUNT.balance` — compile to reads from the new view methods. Replace the prior "deferred" stubs in `compileAccountRef`.
- DSL sizing modes:
  - `pctEquity(frac)` — `qty = (equity * frac) / entryPrice`
  - `pctBalance(frac)` — `qty = (balance * frac) / entryPrice`
  - `riskFrac(frac)` — `qty = (equity * frac) / stopDistance`. Requires a static stop distance (same constraint as 11d1's `riskAbs`).
- `DefaultsMerge` — pure AST function that merges `StrategyAst.defaults` into each `Buy`/`Sell` action's `ActionOpts`. Action-level fields take precedence; defaults fill nulls. Bracket children merge field-wise: if action has a partial bracket, defaults fill the missing slot; if action has no bracket but defaults supplies both stopLoss and takeProfit, an implicit bracket is built.
- `AstCompiler` invokes `mergeDefaults` before passing actions to `ActionCompiler` — every downstream consumer sees a fully-resolved `ActionOpts`.
- Kotlin DSL: `defaults { sizing = ...; stopLoss = ...; takeProfit = ...; tif = ...; orderType = ...; trailing = ... }` block on `StrategyBuilder`. Plus `pctEquity` / `pctBalance` / `riskFrac` sizing helpers.

## Migration from previous phase

Breaking change to `StrategyPnLView`: two new abstract methods. Any existing fake/anonymous implementation must add `override fun equity()` and `override fun balance()`. The two known fakes (`TestStrategyContext.emptyPnL` and the inline fake in `ExprCompilerStateTest`) were updated in this phase.

`Backtest` constructor gains `startingBalance: BigDecimal = BigDecimal.ZERO` as the last parameter — non-breaking thanks to the default. Existing call sites that don't supply a balance get zero, matching prior behavior.

## Usage cookbook

### RISK 1% with DEFAULTS-supplied bracket

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.riskFrac
import com.qkt.dsl.kotlin.strategy
import java.math.BigDecimal

val ast = strategy("disciplined", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    defaults {
        stopLoss = childBy(5.bd)
        takeProfit = childRr(3.bd)
    }
    rule {
        whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
        then { buy(btc, sizing = riskFrac(0.01.bd)) }
    }
}
val strategy = AstCompiler().compile(ast)

val result = Backtest(
    strategies = listOf("disciplined" to strategy),
    ticks = ticks,
    candleWindow = TimeWindow.ONE_MINUTE,
    startingBalance = BigDecimal("10000"),
).run()
```

The action body is short — every entry uses the same risk profile, supplied by `DEFAULTS`. Each fill emits `OrderRequest.Bracket` with `quantity = equity * 0.01 / 5`.

### Equity-throttled trading

```kotlin
import com.qkt.dsl.kotlin.Account
import com.qkt.dsl.kotlin.lt

val ast = strategy("equity_throttle", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever((btc.close gt 100.bd) and (Account.equity gt 9000.bd))
        then { buy(btc, sizing = pctEquity(0.05.bd)) }
    }
}
```

Stops trading when account equity drops below 9000.

### Percent-of-balance sizing

```kotlin
import com.qkt.dsl.kotlin.pctBalance

rule {
    whenever(condition)
    then { buy(btc, sizing = pctBalance(0.10.bd)) }
}
```

Risks 10% of *settled cash* (balance, not equity) per trade.

### DEFAULTS for full action template

```kotlin
import com.qkt.dsl.kotlin.gtc
import com.qkt.dsl.kotlin.limitAt

val ast = strategy("template_driven", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    defaults {
        sizing = riskFrac(0.01.bd)
        orderType = limitAt(99.bd)
        tif = gtc
        stopLoss = childBy(5.bd)
        takeProfit = childRr(3.bd)
    }
    rule {
        whenever(entryCondition)
        then { buy(btc, qty = 0.bd) }  // sizing comes from defaults; qty here is the override hook
    }
}
```

The action body is minimal; every aspect of the order — sizing, entry type, TIF, bracket — comes from `DEFAULTS`. To override a single field on a specific action, supply it explicitly (e.g., `sizing = pctEquity(0.02.bd)` for a higher-risk variant of the same strategy).

> **Note on the `qty = 0.bd` placeholder above:** `ActionScope.buy` currently requires `qty` or `sizing` parameter. To let defaults fully drive sizing, pass an explicit `sizing = ...` (it overrides defaults) or use `qty = 0.bd` as a placeholder that gets overridden when defaults supplies sizing. A no-sizing overload of `buy` could be added in a future phase.

## Testing patterns

For tests of equity-aware sizing, build an inline `StrategyPnLView` fake with the desired `equity()` / `balance()` returns:

```kotlin
val pnl = object : StrategyPnLView {
    override fun realized() = BigDecimal.ZERO
    override fun unrealizedFor(s: String) = BigDecimal.ZERO
    override fun unrealizedTotal() = BigDecimal.ZERO
    override fun total() = BigDecimal.ZERO
    override fun equity() = BigDecimal("10000")
    override fun balance() = BigDecimal("10000")
}
val ctx = testStrategyContext(pnl = pnl)
```

For end-to-end tests that exercise `Backtest` directly, supply `startingBalance` and read `result.global.totalPnL` for outcome assertions.

For DEFAULTS merge logic, prefer the AST-level test (`DefaultsMergeTest`) — verify the merged `ActionOpts` shape directly rather than relying on the downstream compiler.

## Known limitations

- `SYMBOL` placeholder in `DEFAULTS` is **deferred to Phase 11e**. The master spec describes `DEFAULTS { STOP_LOSS = BY ATR(SYMBOL, 14) }` where `SYMBOL` binds at expansion time to the rule's stream. This requires a magic-symbol AST rewrite that pairs more naturally with multi-stream support in 11e. For now, defaults must use concrete stream aliases or stream-independent expressions.
- `Backtest.startingBalance` is uniform across all strategies in the run. Per-strategy balances would need a separate API (e.g., `Map<String, BigDecimal>` or a `withStrategyBalance(...)` builder). Out of scope here.
- `ACCOUNT.drawdown` still rejected — drawdown tracking lives in the post-run `PerformanceReport`, not in the live view. Real-time drawdown computation needs more engine work.
- `OPEN_ORDERS.<sym>` still rejected (broker-side surface needs work; pairs with the deferred CANCEL/CANCEL_ALL from 11c3).
- `riskFrac` sizing requires the same static-stop-distance constraint as `riskAbs`: `BY <numeric literal>` works; `BY <expression>`, `PCT`, `AT` don't.
- `FOR EACH`, multi-stream / multi-timeframe / multi-broker: Phase 11e.
- External `.qkt` parser: Phase 11f.
- CLI runner: Phase 12.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 Phase 11d2
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase11d2.md`
- Merge commit: `cdb7e65`
