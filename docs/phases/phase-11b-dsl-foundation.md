# Phase 11b — DSL Foundation

## Summary

Phase 11b lands the minimum end-to-end DSL slice: an internal Kotlin DSL builds a `StrategyAst`, a compiler turns it into a `Strategy` runnable by the existing engine and backtest. Single-stream, single-timeframe, candle-driven, market-only, direct-quantity sizing. Comparisons, boolean composition, math, and `EMA`/`RSI`/`ATR` indicators. Snapshots, multi-symbol, multi-timeframe, advanced order types, sizing modes, defaults, and the SQL parser arrive in 11c–11f.

The whole AST surface from the master design ships in this phase. Only the subset listed above is wired through the compiler; the rest are declared as data classes and rejected at compile time. This locks the AST contract early so 11c–11f extend the compiler without reshaping AST nodes.

## What's new

- `com.qkt.dsl.ast` — full AST shape: literals (`NumLit`, `BoolLit`), refs (`Ref`, `StreamFieldRef`), operators (`BinaryOp`, `UnaryOp`, `CmpOp`), indicator calls (`IndicatorCall`), rules (`WhenThen`), actions (`Buy`/`Sell`/`Close`/`CloseAll`/`Cancel`/`CancelAll`/`Log`), full sizing/order-type/TIF/bracket/OCO surface declared even where not yet compiled, plus `StrategyAst`, `StreamDecl`, `LetDecl`, `ConstantDecl`, `DefaultsBlock`.
- `com.qkt.dsl.stdlib.Constants` — `HALF_PERCENT`, `ONE_PERCENT`, `TWO_PERCENT`, `THREE_PERCENT`, `FIVE_PERCENT`, `TEN_PERCENT`, `QUARTER_PERCENT`, `BPS`, plus `byName(...)` lookup.
- `com.qkt.dsl.stdlib.IndicatorRegistry` — `EMA`, `RSI`, `ATR` registered with `IndicatorInput` (`NUMERIC_SERIES` vs `CANDLE_SERIES`) so the compiler knows how to feed them.
- `com.qkt.dsl.compile.AstCompiler` — `StrategyAst` → `Strategy`. Resolves `LET` aliases by expression substitution, instantiates one indicator per `IndicatorCall` site, evaluates each `WHEN` condition on every candle, and emits `Signal.Buy`/`Signal.Sell` when conditions hold.
- `Value.Undefined` semantics — undefined is contagious through arithmetic and comparisons. A condition that touches a not-yet-warm indicator evaluates to `Undefined` and the rule does not fire. No null-pointer exceptions during warmup.
- `com.qkt.dsl.kotlin` — internal Kotlin DSL: `strategy(name, version) { ... }`, `stream(...)`, `letting(...)` property delegate, `rule { whenever(...); then { ... } }`, expression operators (`+/-/*/div`, `gt/lt/gte/lte/eq/neq/and/or`), `ema`/`rsi`/`atr` helpers, `buy`/`sell` actions, `bd` extension on numerics for inline literals.

## Migration from previous phase

None. Phase 11b is purely additive.

## Usage cookbook

### Minimal threshold strategy

```kotlin
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.strategy

val ast = strategy("threshold", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever(btc.close gt 100.bd)
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever(btc.close lt 100.bd)
        then { sell(btc, qty = 1.bd) }
    }
}
val strategy = AstCompiler().compile(ast)
```

The compiled `Strategy` plugs into `Backtest` exactly like a hand-written strategy.

### Indicator-driven entry

```kotlin
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy

val ast = strategy("ema_compare", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    rule {
        whenever(fast gt slow)
        then { buy(btc, qty = 1.bd) }
    }
}
```

Note: this fires on **every** candle where `fast > slow`, not only at the cross. Edge-detection (`CROSSES ABOVE`) lands in Phase 11c. To avoid re-entry today, gate with a position check — also Phase 11c (`POSITION.<sym>` accessor).

### Math in conditions

```kotlin
val ast = strategy("range_breakout", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever((btc.high - btc.low) gt 5.bd)
        then { buy(btc, qty = 1.bd) }
    }
}
```

`+`, `-`, `*`, `/` are operator overloads on `ExprAst`. Division uses `Money.CONTEXT` precision.

### Composite condition

```kotlin
import com.qkt.dsl.kotlin.and

rule {
    whenever((fast gt slow) and (btc.volume gt 1000.bd))
    then { buy(btc, qty = 1.bd) }
}
```

### ATR over a candle stream

```kotlin
import com.qkt.dsl.kotlin.atr

val ast = strategy("atr_filter", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val volatility by letting(atr(btc, period = 14))
    rule {
        whenever(volatility gt 0.5.bd)
        then { buy(btc, qty = 1.bd) }
    }
}
```

`atr(stream, period)` is a stream-level helper; `EMA`/`RSI` take a numeric series like `btc.close`.

## Testing patterns

The compiled strategy is a normal `Strategy` — exercise it via `Backtest` with a deterministic tick fixture and compare against a hand-written equivalent. The canonical pattern (see `src/test/kotlin/com/qkt/dsl/compile/HandwrittenEquivalenceTest.kt`):

```kotlin
val dslStrategy = AstCompiler().compile(ast)
val handStrategy = ThresholdStrategy(symbol = "BTCUSDT", threshold = "100".toBigDecimal(), qty = BigDecimal.ONE)

val dslResult = Backtest(strategies = listOf("t" to dslStrategy), ticks = sample, candleWindow = TimeWindow.ONE_MINUTE).run()
val handResult = Backtest(strategies = listOf("t" to handStrategy), ticks = sample, candleWindow = TimeWindow.ONE_MINUTE).run()

assertThat(dslResult.global.totalPnL).isEqualByComparingTo(handResult.global.totalPnL)
assertThat(dslResult.trades.map { it.trade.symbol to it.trade.side })
    .isEqualTo(handResult.trades.map { it.trade.symbol to it.trade.side })
```

The hand-written reference must use the same fires-every-candle semantics — no edge detection, no position guards. Edge-detected reference strategies cannot be matched until Phase 11c lands `CROSSES`.

The unsupported-AST boundary is also tested explicitly (`UnsupportedAstTest.kt`): `BETWEEN`, `IN`, `CROSSES`, `CASE WHEN`, `Aggregate`, `AccountRef`, `PositionRef` all produce `IllegalStateException("ExprCompiler: unsupported expression: ...")` in 11b. As 11c implements them, swap one `error` for an implementation; nothing else moves.

## Known limitations

- Single stream, single timeframe, single broker per strategy. Multi-stream rules still parse into the AST but the compiler ignores all but the active stream's candles. Multi-stream / multi-timeframe / multi-broker arrives in Phase 11e.
- No snapshots (`@buy`, `@sell`, `@open`, `@T-N`). `LetResolver` rejects any `Ref` with a non-null `snapshot`. (Phase 11c.)
- No `CROSSES`, `BETWEEN`, `IN`, `CASE WHEN`, running aggregates (`MIN`/`MAX`/`MEAN`/`SUM SINCE OPEN|T-N`). (Phase 11c.)
- No `ACCOUNT.*`, `POSITION.*`, `POSITION_AVG_PRICE.*`, `OPEN_ORDERS.*` accessors. (Phase 11c.)
- Indicator-on-indicator composition (e.g. `RSI(ATR(s, 14), 5)`) is not supported. The series argument of an `IndicatorCall` must be a `StreamFieldRef`. (Phase 11c.)
- No `LIMIT`, `STOP`, `STOP-LIMIT`, `TRAILING`, `BRACKET`, `OCO`. Only market orders. (Phase 11d.)
- No `RISK`, `% OF EQUITY`, `% OF BALANCE`, `USD` notional, `POSITION.<sym>` sizing. Only direct quantity. (Phase 11d.)
- No TIF (`GTC`/`IOC`/`FOK`/`DAY`/`GTD`). Defaults to whatever the engine applies. (Phase 11d.)
- No `DEFAULTS` block at runtime. The `DefaultsBlock` AST type exists but is ignored. (Phase 11d.)
- No `Close`, `CloseAll`, `Cancel`, `CancelAll`, `Log` actions. (Phase 11c covers `Close*`/`Cancel*`; `Log` lands with 11c too.)
- No `FOR EACH` macro. (Phase 11e.)
- No external `.qkt` parser. Kotlin DSL only. (Phase 11f.)
- No CLI runner. (Phase 12 — designed in master spec but out of scope for the 11 series.)
- Rule semantics is "fires on every candle the condition is true." Re-entry is the user's problem until `POSITION.*` and `CROSSES` arrive in 11c.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11b.md`
- Merge commit: `a167370`
