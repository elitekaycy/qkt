# Phase 36 — Armed trailing stop

## Summary

`STOP LOSS TRAILING <distance> AFTER MFE >= <threshold>` is now a first-class bracket leg. The stop sits at a fixed distance from the entry until the trade's maximum favorable excursion (MFE) crosses the threshold, then begins trailing the running favorable extreme at the same distance. Closes the GAP 1 parity item in `hedge-straddle.qkt` (#48).

## What's new

- New token `AFTER` (between `WITHIN` and `MFE`).
- New AST node `ChildArmedTrail(trailDistance, mfeThreshold)`.
- New sealed type `com.qkt.execution.StopLossSpec` with `Fixed(price)` and `ArmedTrail(distance, threshold)` variants. `OrderRequest.Bracket.stopLoss` now carries this type instead of a bare `BigDecimal` — every future stop variant (volatility-based, time-based, indicator-triggered) plugs in as one new sealed-class member.
- New `OrderRequest.ArmedTrailingStop` variant — the engine-managed stop that the bracket-fallback path emits for `StopLossSpec.ArmedTrail`.
- New compiler dispatch in `ChildPriceResolver.compileStopLoss` that returns either `CompiledStopLoss.Static(ArmedTrail)` (literal distance/threshold) or `CompiledStopLoss.Dynamic(...)` (per-tick resolution to `Fixed`).
- `OrderManager` gains a per-order arming-state map (`armedTrailArmed`) and reuses the existing `trailingHwm` infrastructure for the post-arm trail.
- Risk-based sizing (`SIZING RISK $ N`) resolves the armed-trail distance as the worst-case stop distance.

## Migration from previous phase

Code that constructs `OrderRequest.Bracket` directly must wrap the stop price in `StopLossSpec.Fixed`:

```kotlin
// Before
OrderRequest.Bracket(..., stopLoss = BigDecimal("100"), ...)

// After
OrderRequest.Bracket(..., stopLoss = StopLossSpec.Fixed(BigDecimal("100")), ...)
```

This sweeps cleanly across the 15-ish call sites that build brackets in tests and one production producer (`StackEngine`). The DSL-facing surface is unchanged: existing strategies parse and compile unmodified.

## Usage cookbook

### Worked example: hedge-straddle GAP 1

```qkt
STRATEGY hedge_straddle VERSION 2

SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m

RULES
    WHEN NOW.minute_utc = 55 AND NOW.hour_utc IN [1,2,7,8] AND POSITION.gold = 0
    THEN BUY gold
      ORDER_TYPE = BUY_STOP AT gold.close + 50
      SIZING RISK $ 350
      BRACKET {
        STOP LOSS TRAILING 1800 AFTER MFE >= 1800,
        TAKE PROFIT BY 3600
      }
```

Reading: place a buy stop 50 pips above current; once filled, hold a wide fixed stop at `entry − 1800` until the position has gained 1800 pips of favorable movement, then trail 1800 pips behind the running high. The worst-case loss is bounded by the 1800-pip distance throughout. This matches the pa-quant reference behavior.

### Worked example: simple session-breakout trail

```qkt
WHEN btc.close = open_of_day AND POSITION.btc = 0
THEN BUY btc SIZING 0.1 BRACKET {
  STOP LOSS TRAILING 5 AFTER MFE >= 10,
  TAKE PROFIT BY 50
}
```

Reading: enter on the daily open touch, hold a $5 stop until $10 of profit has been seen, then trail $5 behind the high. If the trade never gains $10, exits at `entry − 5`.

### Worked example: SELL side

```qkt
WHEN btc.close > 100000 AND POSITION.btc = 0
THEN SELL btc SIZING 0.1 BRACKET {
  STOP LOSS TRAILING 100 AFTER MFE >= 200,
  TAKE PROFIT BY 1000
}
```

Reading: pre-arm stop at `entry + 100` (above the short entry). When MFE reaches 200 (price has fallen by 200), arm — stop drops to `lwm + 100` and trails the low. Exits if price rises back to that level.

### Worked example: trail-from-inception (threshold = 0)

```qkt
BUY btc SIZING 0.1 BRACKET {
  STOP LOSS TRAILING 5 AFTER MFE >= 0,
  TAKE PROFIT BY 50
}
```

`AFTER MFE >= 0` means the stop is armed on the first tick post-fill — equivalent to a regular trailing stop. The explicit syntax documents the intent.

## Testing patterns

`ArmedTrailEndToEndTest` exercises the full DSL → AstCompiler → OrderManager bracket-fallback chain with deterministic tick sequences. The canonical pattern:

```kotlin
private fun ticks(prices: List<String>): List<Tick> =
    prices.mapIndexed { i, p ->
        Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
    }

val result =
    Backtest(
        strategies = listOf("name" to compile(src)),
        ticks = ticks(listOf("100", "102", "108", "112", "108", "106", "106")),
        candleWindow = TimeWindow.ONE_MINUTE,
    ).run()
```

When designing tick sequences for armed-trail tests:

- The strategy's entry condition fires on candle close — the next tick's price is the actual fill price, and `lastObservedPrice` at submit time anchors the engine's `entryPrice` for the trail.
- Allow at least one trailing tick of padding after the stop fires so the resulting market order can fill.
- For the never-armed case, design ticks so MFE never reaches threshold.
- For the armed-then-triggered case, push hwm past `entry + threshold`, then retrace past `hwm − distance`.

`ChildPriceResolverArmedTrailTest` covers compiler-level invariants (positive literals, dispatch to `Static`). `OrderRequest.ArmedTrailingStop` value-type invariants live in `ArmedTrailingStopTest`. The parser surface is covered by `ParserArmedTrailingStopTest`.

## Known limitations

- **Armed state resets on daemon restart.** The persistor does not currently save `Bracket` or `ArmedTrailingStop` instances (the persistor stores only client-order-ID references for OCO leg pairs). After a restart, `MT5StateRecovery` rebuilds the OCO pair from the broker's open orders, but the per-order arming flag and `hwm` start from zero. A position that was armed before restart will re-anchor to its (recovered) entry price post-restart. Acceptable in the current single-strategy-per-magic prod setup; revisit when a bracket lasts longer than a typical daemon uptime.
- **`<distance>` and `<threshold>` must be numeric literals.** Expression-typed distance/threshold are rejected at compile time. Strategies that need dynamic distances should compute them inline as a fixed `BY` stop and update at deploy time.
- **PERCENT trail distance is not supported.** Only absolute distance for now.
- **One-time arming.** Once armed, the stop never disarms — a retreat below `threshold` does not reset the trail.
- **`WITHIN <duration>` modifier is not supported.** Time-bounded arming (e.g. "arm only if MFE crossed in the first hour") would compose with the existing armed-trail shape; defer until a real strategy needs it.
- **`TAKE PROFIT TRAILING` is rejected at parse time.** A trailing target is fundamentally a moving target — model that as a regular `BY/RR` exit or a separate stop.

## References

- Spec: [`docs/superpowers/specs/2026-05-25-phase36-armed-trailing-stop-design.md`](../superpowers/specs/2026-05-25-phase36-armed-trailing-stop-design.md)
- Plan: [`docs/superpowers/plans/2026-05-29-phase36-armed-trailing-stop.md`](../superpowers/plans/2026-05-29-phase36-armed-trailing-stop.md)
- Issue: [#48](https://github.com/elitekaycy/qkt/issues/48)
- Reference DSL: [Armed trailing stop](../reference/dsl/bracket.md#armed-trailing-stop)
- Predecessor: Phase 25C — `TRAILING BY` end-to-end ([#126](https://github.com/elitekaycy/qkt/issues/126))
