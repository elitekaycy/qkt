# Phase 11c2 — Snapshots and Running Aggregates

## Summary

Phase 11c2 adds **stateful runtime** to the DSL. Strategies can now capture LET values at decision time (`@buy`, `@sell`, `@open`, `@T-N`) and summarise series over rolling/since-open windows (`runMin` / `runMax` / `runMean` / `runSum`). Trailing stops, breakeven exits, "highest high since entry" gates, and N-bar momentum filters are now expressible in pure DSL.

The headline acceptance test: a chandelier-style trailing stop using `runMax(btc.close, sinceOpen)` produces a bit-identical `BacktestResult` to a hand-written reference strategy. Reset semantics on position transitions, snapshot-store lifecycle, and per-rule symbol routing all proved correct on first end-to-end run.

## What's new

- `SnapshotStore` — runtime store keyed by `(symbol, name, kind)` for `@buy`/`@sell`/`@open` slots, plus per-`(symbol, name)` ring buffer for `@T-N`.
- `SnapshotPlan` — compile-time scan of all rule expressions; collects `(name, kind)` pairs that need capture and the max `N` for each rolling buffer.
- `AggregateState` — pure stateful aggregator with two flavours: `SinceOpen` (running min/max/sum/count + reset) and `SinceT` (ring buffer of N values).
- `AggregateBinding.Bag` — registry of all `Aggregate` instances in the strategy; `CompiledStrategy` updates each on every candle and resets the `SinceOpen` ones on position-open transitions.
- `PositionTransitions` — per-stream `prev_qty` tracker; `observe()` returns one of `Stay` / `OpenedFromZero` / `ClosedToZero` / `Flipped`.
- `EvalContext.snapshotStore` — added with a default `SnapshotStore(emptyMap())` so 11b/11c1 tests stayed untouched.
- `LetResolver` — keeps snapshot Refs intact (does not substitute their RHS) so they can route to the SnapshotStore at compile time. Validates the LET name exists.
- `ExprCompiler.compile(expr, ruleSymbol)` — optional `ruleSymbol` parameter. Snapshot Refs and `Aggregate(SinceOpen)` close over the symbol at compile time.
- `CompiledRule` — now carries `ruleSymbol`, `isBuy`/`isSell`, and per-rule snapshot capture lists. On fire: detects whether the action is opening (pre-fire `position == 0`), captures `@buy`/`@sell`/`@open` slots, then emits the Signal.
- `CompiledStrategy.onCandle` — full snapshot/aggregate lifecycle: position-transition observation → @open clear / SINCE-OPEN reset → indicator updates → per-candle rolling capture → aggregate updates → rule fire.
- Kotlin DSL helpers:
  - `at` infix on `Ref`: `fast at atBuy`, `fast at atOpen`, `fast at atT(3)`.
  - `runMin` / `runMax` / `runMean` / `runSum` over `sinceOpen` or `sinceT(n)`.

## Migration from previous phase

`EvalContext` gains `snapshotStore` with a default value — non-breaking for 11b/11c1 callers. `ExprCompiler.compile` gains an optional `ruleSymbol` parameter — also non-breaking.

`CompiledRule` constructor changed: now requires `ruleSymbol`, `isBuy`, `isSell`, and three capture lists. Internal class — only `AstCompiler` constructs it, so no caller-facing migration.

## Usage cookbook

### Trailing stop with `runMax sinceOpen`

```kotlin
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.minus
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.runMax
import com.qkt.dsl.kotlin.sinceOpen
import com.qkt.dsl.kotlin.strategy

val ast = strategy("trail", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    val hwm by letting(runMax(btc.close, sinceOpen))
    rule {
        whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever((position(btc) gt 0.bd) and (btc.close lt (hwm - 5.bd)))
        then { sell(btc, qty = 1.bd) }
    }
}
```

The `hwm` aggregate auto-resets on every position open and every position close; while flat, the LET evaluates to `Undefined` and the exit rule's compound condition short-circuits.

### Breakeven exit with `entry@open`

```kotlin
import com.qkt.dsl.kotlin.at
import com.qkt.dsl.kotlin.atOpen

val ast = strategy("breakeven", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val entry by letting(btc.close)
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    rule {
        whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever((position(btc) gt 0.bd) and (btc.close lt (entry at atOpen)))
        then { sell(btc, qty = 1.bd) }
    }
}
```

`entry at atOpen` reads the value of `entry` (which evaluates to `btc.close`) at the moment the long position opened.

### N-bar momentum filter with `runMean sinceT`

```kotlin
import com.qkt.dsl.kotlin.runMean
import com.qkt.dsl.kotlin.sinceT

val ast = strategy("momentum_filter", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val baseline by letting(runMean(btc.close, sinceT(20)))
    rule {
        whenever((btc.close gt baseline) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
}
```

`baseline` returns `Undefined` until the rolling window has 20 samples — entries stay parked during warmup.

### "Was X true 3 bars ago?" with `@T-N`

```kotlin
import com.qkt.dsl.kotlin.at
import com.qkt.dsl.kotlin.atT

val ast = strategy("delayed_confirm", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    val gap by letting(fast - slow)
    rule {
        // Enter only if the fast/slow gap was already positive 3 bars ago and is still positive now
        whenever((gap gt 0.bd) and ((gap at atT(3)) gt 0.bd) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
}
```

`gap at atT(3)` reads the `gap` value captured 3 candles ago.

## Testing patterns

End-to-end equivalence tests follow the `TrailingStopRef` pattern: write a hand-written `Strategy` with the same fires-every-candle semantics (no edge detection beyond what `crossesAbove` provides), then assert that the DSL and reference produce identical `BacktestResult.totalPnL` and trade sequence over a deterministic tick fixture.

Reset semantics is the subtlest piece: when the position closes, `@open` slots clear AND `SINCE-OPEN` aggregates reset, so the next entry starts with fresh state. The `TrailingStopRef` mirrors this by setting `runningMax = null` on both `wasZero && !isZero` and `!wasZero && isZero` transitions.

For unit tests of the runtime stores (`SnapshotStore`, `AggregateState`, `PositionTransitions`), construct them directly and exercise their state machines — they're pure types with no `EvalContext` dependency.

## Known limitations

- Position-transition detection is per-`onCandle` polling against `StrategyContext.positions`. With synchronous backtest brokers, this works correctly because position state updates between candles. Live mode with asynchronous fills may need a different mechanism.
- `@open` clears on **both** `ClosedToZero` and `Flipped` transitions. The current 11c2 single-stream world cannot flip (no short selling on the same instrument from a long position via one Buy/Sell action), but the code is conservative.
- Snapshot capture is per-strategy single-stream-symbol implicit. Multi-stream support (Phase 11e) will add explicit symbol routing on Refs.
- Only `Buy`/`Sell` action rules can capture snapshots. `Log`, `CLOSE`, `CANCEL` actions arrive in 11c3.
- LET RHSs containing snapshot Refs are not supported (would require recursive symbol resolution). Snapshot Refs must appear in rule conditions or sizing expressions.
- `OPEN_ORDERS.<sym>` accessor still rejected — engine surface needs work.
- `LIMIT` / `STOP` / `BRACKET` / `OCO` / `TRAILING` / TIF / advanced sizing: Phase 11d.
- `FOR EACH`, multi-stream / multi-timeframe / multi-broker: Phase 11e.
- External `.qkt` parser: Phase 11f.
- CLI runner: Phase 12 (deferred).

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 Phase 11c2
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11c2.md`
- Merge commit: TBD (filled in after merge to `main`).
