# Phase 32 — bid/ask DSL exposure

**Status:** design
**Date:** 2026-05-21

## Phase

Phase 32. Engine + DSL.

## Goal

Expose `gold.bid`, `gold.ask`, and `gold.spread` to DSL strategies, so a strategy
can gate on the bid/ask spread — e.g. `WHEN gold.spread < 5.0`. bid/ask already
flow on every `Tick` and are populated by the live MT5 feed; today a DSL strategy
has no way to read them.

## Background

- `Tick` already carries `bid`, `ask`, `bidVolume`, `askVolume`, with derived
  `mid` and `spread` properties.
- `Mt5TickFeedSource` already populates `bid`/`ask` on every tick from the
  gateway `/symbol_info_tick` endpoint.
- `Candle` carries only OHLCV — no bid/ask.
- The DSL evaluates every `gold.X` expression against the closed `Candle`:
  `ExprCompiler` maps `close/open/high/low/volume` to `Candle` fields, and
  `gold.price` is an alias for `candle.close`. There is no tick-level accessor.
- Motivating use case: hedge-straddle GAP 3 — a spread gate to skip
  toxic-spread sessions.

## Non-goals

- Backtest bid/ask data (CSV columns, Dukascopy bid/ask fetch, historical store
  schema). Backtest spread fidelity is Phase 33 territory. In backtest, and on
  any feed without bid/ask, `gold.spread` resolves to null.
- Editing `hedge-straddle.qkt` to use the spread gate — a separate follow-up in
  the qkt-prod repo.
- Indicator *series* over spread (`SMA(gold.spread, …)`). Scalar use only.
- Placement-instant spread accuracy (a synchronous query in the order-submit
  path) — see Accuracy bound.

## Design

### Candle model

Add to `Candle`: `bid: BigDecimal? = null` and `ask: BigDecimal? = null`
(stored). Add `mid` and `spread` as derived `get()` properties — null when
either side is absent — mirroring `Tick` exactly. Only `bid`/`ask` are stored;
derived values are computed lazily on access.

### Candle aggregator

On candle close, snapshot `bid`/`ask` from the last tick in the window — the
same tick that determines `close`. If that tick's bid/ask are null, the candle's
stay null. No new aggregator state; the aggregator already walks the ticks.

### DSL surface

- `StreamRef` — add `bid`, `ask`, `spread` properties producing
  `StreamFieldRef(alias, "bid"|"ask"|"spread")`.
- `ExprCompiler` and `IndicatorBinding` — add `bid`/`ask`/`spread` to the scalar
  field-name validation sets; resolve them to `candle.bid` / `candle.ask` /
  `candle.spread`.
- The DSL text parser must accept `.bid` / `.ask` / `.spread` after a stream
  alias.

### Null semantics

A `StreamFieldRef` can now resolve to null. The expression evaluator gains
minimal null tolerance: a comparison with a null operand evaluates to false, so
a `WHEN` condition referencing an unknown spread does not fire. No crash, and no
dependency on the planned `IS NULL` / `IS NOT NULL` (Phase 24). Arithmetic on a
null field propagates null, and a null `WHEN` result is non-firing. The exact
evaluator change is sized in the implementation plan after a full read of
`ExprCompiler` — today every stream field is non-null `BigDecimal`.

## Accuracy bound

`gold.bid` / `.ask` / `.spread` reflect the **last tick the feed delivered
before the candle closed** — the freshest value the engine holds. It is not the
live market bid/ask at the instant the order reaches MT5. Two gaps, both
inherent and small for a liquid instrument:

1. Staleness between the snapshot tick and actual order placement (last tick →
   rule evaluation → dispatch → MT5).
2. The MT5 gateway feed is polled; ticks are samples at the poll cadence.

At a candle-boundary rule the candle-carried value equals what a "latest tick"
model would yield, so no accuracy is lost versus the heavier option. Placement-
instant accuracy would require a synchronous bid/ask query in the order-submit
path, which is explicitly out of scope. Strategy authors should treat a spread
gate as "spread was ~X a moment ago", not "spread is exactly X right now".

## Testing

- `Candle` — bid/ask stored; `mid`/`spread` derived correctly; null when either
  side absent.
- Candle aggregator — snapshots last-tick bid/ask; candle bid/ask stay null when
  the ticks carry none.
- DSL — `gold.bid` / `.ask` / `.spread` compile and resolve to candle fields.
- Null — a `WHEN gold.spread < X` rule does not fire when spread is null, and
  does not crash.
- End-to-end — a compiled strategy with `WHEN gold.spread < X` evaluates
  correctly on candles that carry bid/ask.

## Risks

Low–medium. The null tolerance in `ExprCompiler`'s evaluator is the one area to
watch: today every stream field is non-null `BigDecimal`. The plan sizes the
exact change after reading the evaluator. Everything else — `Candle` fields, the
aggregator snapshot, `StreamRef` — is additive and mirrors existing patterns.

## References

- Backlog: `docs/backlog.md`, Tier 4 — Phase 32.
- Motivating gap: hedge-straddle GAP 3 (strategy header, qkt-prod repo).
