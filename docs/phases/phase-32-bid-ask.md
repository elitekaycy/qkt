# Phase 32 — bid/ask DSL exposure

## Summary

Phase 32 makes the bid/ask spread reachable from a strategy. bid/ask already
arrived on every `Tick` and were populated by the live MT5 feed, but a DSL
strategy had no way to read them. This phase carries bid/ask onto the `Candle`
and exposes `<stream>.bid` / `.ask` / `.spread` to the DSL, so a strategy can
gate on the spread — e.g. skip a session when the spread is toxic.

## What's new

- `Candle.bid` / `Candle.ask` — nullable; the quote from the last tick in the
  candle window.
- `Candle.mid` / `Candle.spread` — derived, computed on access, `null` when
  either side is absent.
- `CandleAggregator` carries bid/ask through aggregation (last tick wins, the
  same rule as `close`).
- DSL stream fields `<stream>.bid` / `.ask` / `.spread` — resolve to the
  candle's quote; resolve to undefined (the rule does not fire) when the feed
  carries no quote.
- `StreamRef.bid` / `.ask` / `.spread` — the same accessors for the Kotlin DSL.

## Migration from previous phase

None. The change is additive: `Candle`'s new `bid`/`ask` parameters default to
`null`, so every existing `Candle(...)` call site is unchanged.

## Usage cookbook

### Spread gate (text DSL)

The motivating case — only enter when the spread is sane:

```qkt
RULES
    WHEN now.minute_utc = 55
     AND gold.spread < 5.0
    THEN OCO_ENTRY { ... }
```

When the feed carries no quote (backtest, or a non-MT5 feed), `gold.spread` is
undefined and the whole `WHEN` does not fire — the same null-tolerant behaviour
as an out-of-range candle lookback.

### Reading bid and ask directly

```qkt
WHEN gold.ask - gold.bid > 3.0
THEN LOG "wide spread on gold"
```

### Kotlin DSL

`StreamRef` exposes `bid` / `ask` / `spread` alongside `close` / `open` / etc.,
producing the same `StreamFieldRef` AST nodes the text DSL produces — so a
Kotlin-DSL strategy reads the spread exactly the way it reads the close.

## Testing patterns

The canonical fixture is a `Candle` built with `bid`/`ask` set, evaluated
through `ExprCompiler`:

```kotlin
val quoted = candle.copy(bid = BigDecimal("104"), ask = BigDecimal("106"))
val ctx = EvalContext(candle = quoted, streams = ..., lets = emptyMap(), strategyContext = ...)
val v = ExprCompiler().compile(StreamFieldRef("btc", "spread")).evaluate(ctx)
// v == Value.Num(2)
```

- `CandleTest` — `mid`/`spread` derivation, including the null cases.
- `CandleAggregatorTest` — a closed candle carries the last tick's bid/ask, and
  stays null when the ticks carry none.
- `ExprCompilerStreamFieldTest` — `bid`/`ask`/`spread` resolve; resolve to
  `Value.Undefined` with no quote; a spread comparison is `Bool` when quoted and
  `Undefined` when not.
- `StreamRefTest` — the Kotlin-DSL accessors produce the right `StreamFieldRef`.

## Known limitations

- **Live-feed only.** Backtest and historical feeds carry no bid/ask, so
  `gold.spread` is undefined there. Spread-aware backtest fidelity is Phase 33
  (`MT5BrokerSimulator`).
- **Freshest-observed, not placement-instant.** bid/ask is the quote from the
  last tick before the candle closed. There is a staleness gap to the moment an
  order actually reaches the venue, and the MT5 feed is polled. See the Phase 32
  spec's "Accuracy bound" section. Treat a spread gate as "spread was ~X a
  moment ago", not "spread is exactly X now".
- **Scalar use only.** Indicator series over spread (`SMA(gold.spread, …)`) is
  not supported.

## References

- Spec: [`docs/superpowers/specs/2026-05-21-phase32-bid-ask-dsl-design.md`](../superpowers/specs/2026-05-21-phase32-bid-ask-dsl-design.md)
- Plan: [`docs/superpowers/plans/2026-05-21-phase32-bid-ask-dsl.md`](../superpowers/plans/2026-05-21-phase32-bid-ask-dsl.md)
- Merge commit: _added on merge_
