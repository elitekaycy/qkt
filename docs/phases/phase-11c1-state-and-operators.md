# Phase 11c1 — Engine State, Range, Conditional, Cross, Composition, Math

## Summary

Phase 11c1 turns the 11b foundation into an actually expressive language. Strategies can now read live engine state (account P&L, position size, average entry price), gate on ranges and membership, branch with `CASE WHEN`, detect price crosses, compose indicators, and call the standard math library — all without per-rule runtime state. Snapshots and running aggregates land in 11c2; new actions (`Log`, `CLOSE`, `CANCEL`) land in 11c3.

The headline acceptance test: a Kotlin DSL EMA crossover with `crossesAbove` / `crossesBelow` now produces a bit-identical `BacktestResult` to the hand-written `EmaCrossoverStrategy`. 11b's `HandwrittenEquivalenceTest` could only match a fires-every-bar threshold strategy; 11c1's `CrossEquivalenceTest` matches the canonical edge-detected entry/exit pattern.

## What's new

- `EvalContext.strategyContext` — the compiled strategy threads the live `StrategyContext` into every expression evaluation, so state accessors can read `pnl` and `positions`.
- `AccountRef` compile — `realized_pnl`, `unrealized_pnl`, `total_pnl`. Reads from `StrategyPnLView`.
- `PositionRef` compile — signed quantity from `StrategyPositionView`.
- `StateAccessor(POSITION_AVG_PRICE, alias)` compile — `Position.avgEntryPrice`. `OPEN_ORDERS` remains rejected (broker surface needs work first).
- `Between`, `InList`, `CaseWhen` compile.
- `Crosses ABOVE` / `Crosses BELOW` with per-binding `CrossesState` for prev-bar tracking. `Undefined` does not corrupt the prev-bar slot — warmup is safe.
- Indicator-on-indicator composition. `IndicatorBinding.Bag.bind` recurses on nested `IndicatorCall` series args; the inner binding's output drives the outer indicator's update.
- `FuncCall` AST node + `FuncRegistry` stdlib: `ABS`, `SQRT`, `LOG` (single-arg), `MIN`, `MAX` (variadic ≥2). Distinct from running-aggregate `MIN`/`MAX` over a `SINCE` window (11c2).
- Kotlin DSL helpers: `Account.realizedPnl/unrealizedPnl/totalPnl`, `position(stream)`, `positionAvgPrice(stream)`, `between`, `inList`, `crossesAbove`, `crossesBelow`, `caseWhen(...)`, `abs/sqrt/log/min/max`.

## Migration from previous phase

`EvalContext` now requires `strategyContext: StrategyContext`. Tests that constructed `EvalContext` directly need to pass `testStrategyContext()` from `com.qkt.strategy`. Production code goes through `AstCompiler`, which threads `StrategyContext` automatically — no caller-side changes needed for compiled strategies.

## Usage cookbook

### Cross-detected EMA crossover (the canonical entry pattern)

```kotlin
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.crossesBelow
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.strategy

val ast = strategy("ema_x", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    rule {
        whenever(fast crossesAbove slow)
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever(fast crossesBelow slow)
        then { sell(btc, qty = 1.bd) }
    }
}
val strategy = AstCompiler().compile(ast)
```

Now equivalent to the hand-written `EmaCrossoverStrategy`. See `CrossEquivalenceTest`.

### Position-aware entry guard

```kotlin
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.strategy

val ast = strategy("guarded_entry", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    rule {
        whenever((fast gt slow) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
}
```

`position(btc)` reads the signed quantity from `StrategyPositionView`. The rule fires every candle the condition is true, but the `position == 0` guard prevents repeated entries.

### Account-PnL throttle

```kotlin
import com.qkt.dsl.kotlin.Account
import com.qkt.dsl.kotlin.gt

val ast = strategy("throttled", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever((btc.close gt 100.bd) and (Account.totalPnl gt (-100).bd))
        then { buy(btc, qty = 1.bd) }
    }
}
```

The strategy stops adding longs once it's down more than 100 in total P&L.

### RSI of ATR — indicator composition

```kotlin
import com.qkt.dsl.kotlin.atr
import com.qkt.dsl.kotlin.rsi

val ast = strategy("rsi_of_atr", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val volBoost by letting(rsi(atr(btc, period = 14), period = 5))
    rule {
        whenever(volBoost gt 70.bd)
        then { buy(btc, qty = 1.bd) }
    }
}
```

`rsi(atr(btc, 14), 5)` reads "the 5-period RSI of the 14-period ATR." The compiler chains the bindings so each candle: ATR updates first, its output feeds RSI, RSI's output is what `volBoost` returns.

### Range filter with `BETWEEN`

```kotlin
import com.qkt.dsl.kotlin.between
import com.qkt.dsl.kotlin.rsi

val ast = strategy("rsi_band", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val r by letting(rsi(btc.close, period = 14))
    rule {
        whenever(r.between(30.bd, 70.bd))
        then { buy(btc, qty = 1.bd) }
    }
}
```

### `CASE WHEN` for conditional sizing

```kotlin
import com.qkt.dsl.kotlin.caseWhen
import com.qkt.dsl.kotlin.gt

val ast = strategy("tiered_size", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val r by letting(rsi(btc.close, period = 14))
    rule {
        whenever(btc.close gt 100.bd)
        then {
            buy(
                btc,
                qty =
                    caseWhen(
                        r gt 70.bd to 0.5.bd,
                        r gt 50.bd to 1.bd,
                        elseExpr = 2.bd,
                    ),
            )
        }
    }
}
```

The buy size is 0.5 when overbought, 2.0 when oversold, 1.0 otherwise. (`CASE WHEN` is an expression, usable anywhere a numeric value fits.)

### Math stdlib in conditions

```kotlin
import com.qkt.dsl.kotlin.abs
import com.qkt.dsl.kotlin.atr
import com.qkt.dsl.kotlin.max
import com.qkt.dsl.kotlin.min

val ast = strategy("vol_aware", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val v by letting(atr(btc, period = 14))
    val capped by letting(min(v, 5.bd))
    val floored by letting(max(capped, 1.bd))
    rule {
        whenever(abs(btc.close - 100.bd) gt floored)
        then { buy(btc, qty = 1.bd) }
    }
}
```

`min(v, 5.bd)` and `max(capped, 1.bd)` are variadic stdlib functions, distinct from the running-aggregate forms (which arrive in 11c2).

## Testing patterns

State-aware tests use the existing `testStrategyContext(...)` helper to inject custom `pnl` / `positions` views:

```kotlin
val pos = object : StrategyPositionView {
    override fun positionFor(symbol: String) = if (symbol == "BTCUSDT") Position("BTCUSDT", BigDecimal("2.5"), BigDecimal("100")) else null
    override fun allPositions() = emptyMap<String, Position>()
}
val ec = EvalContext(
    candle = candle,
    streamSymbols = mapOf("btc" to "BTCUSDT"),
    lets = emptyMap(),
    strategyContext = testStrategyContext(positions = pos),
)
```

Cross-detection tests rely on a sequence of candles to hit every transition; a single bar evaluation is `Undefined` (no prev-bar yet). See `ExprCompilerCrossesTest`.

End-to-end equivalence tests against hand-written strategies use the same `Backtest` harness, asserting on `totalPnL` and the trade sequence. See `CrossEquivalenceTest`.

## Known limitations

- `ACCOUNT.equity`, `ACCOUNT.balance`, `ACCOUNT.drawdown`, `OPEN_ORDERS.<sym>` not yet exposed — engine surface needs work first. Keep them in `AccountRef` / `StateAccessor` AST shape so adding them is purely a runtime change.
- Snapshots (`@buy`, `@sell`, `@open`, `@T-N`): Phase 11c2.
- Running aggregates (`MIN`/`MAX`/`MEAN`/`SUM` over a `SINCE OPEN`/`SINCE T-N` window): Phase 11c2.
- `Log`, `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL` actions: Phase 11c3.
- `LIMIT`, `STOP`, `BRACKET`, `OCO`, `TRAILING`, TIF, advanced sizing modes (`RISK`, `% OF EQUITY`, `USD` notional, `POSITION.<sym>` close), `DEFAULTS` block: Phase 11d.
- `FOR EACH`, multi-stream / multi-timeframe / multi-broker: Phase 11e.
- External `.qkt` parser: Phase 11f.
- CLI runner: Phase 12 (designed in master spec, deferred).
- Rule semantics is still "fires on every candle the condition is true." `crossesAbove`/`crossesBelow` plus `position(stream) eq 0.bd` are the idioms for non-spammy entries.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 Phase 11c1
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11c1.md`
- Merge commit: TBD (filled in after merge to `main`).
