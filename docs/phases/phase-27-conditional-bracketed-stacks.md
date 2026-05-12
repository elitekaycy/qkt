# Phase 27 — Conditional Bracketed Stacks (`STACK_AT`)

**Status:** Shipped on `phase-27-impl`. Open in PR #9.
**Spec:** [`../superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md`](../superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md)
**Plan:** [`../superpowers/plans/2026-05-12-phase27-conditional-bracketed-stacks.md`](../superpowers/plans/2026-05-12-phase27-conditional-bracketed-stacks.md)

---

## Summary

Phase 27 lets a strategy attach N independent "stack" sub-trades to a primary entry. Each `STACK_AT` clause fires once when the primary leg's max favorable excursion crosses a threshold within a time window — emitting a fresh bracketed market order that tracks as its own leg with its own SL/TP. Closing the primary does NOT close the stacks; a stack hitting its own TP does not affect the primary or other stacks. This is the multi-leg pattern from the production hedge-straddle and unlocks the ~148% P&L driver the pa-quant analysis attributes to stacking.

Two architectural changes carry the feature: a singular `Position` per (strategy, symbol) becomes a `LegBook` of `PositionLeg`s (PRIMARY + N STACK), and `StrategyPositionTracker.applyFill` learns to route stack-tagged fills as STACK legs instead of averaging them into the primary. Existing `POSITION.<stream>` accessors keep returning the netted view, so strategies that don't use `STACK_AT` see no behavior change.

---

## What's new

### DSL surface

- `STACK_AT MFE >= <threshold> WITHIN <duration> SIZING <qty> BRACKET { ... }` — clause that attaches to a `BUY`/`SELL` action. Multiple clauses per action allowed; each fires independently.
- `POSITION.<stream>.mfe` — DSL expression returning the PRIMARY leg's current MFE (price units; `0` when no primary exists).

### AST

- `com.qkt.dsl.ast.StackAtClause` — `(mfeThreshold: ExprAst, withinDuration: DurationAst, sizing: SizingAst, bracket: BracketAst)`.
- `com.qkt.dsl.ast.ActionOpts.stackAts: List<StackAtClause>` — populated by the parser; empty when no `STACK_AT` appears.
- `com.qkt.dsl.ast.StateSource.POSITION_MFE` — new state-accessor source.

### Compile-time

- `com.qkt.dsl.compile.StackAtCompiler` — folds each clause's threshold / sizing / bracket distances to `BigDecimal` constants. Rejects non-literal sizing, non-`ChildBy` brackets, and references / indicators / `NOW.<field>` in the threshold expression.
- `com.qkt.dsl.compile.CompiledStackTier` — `(mfeThreshold, withinMs, stackQuantity, slDistance, tpDistance)`. Consumed only by `StackEngine`.
- `com.qkt.dsl.compile.PendingStacks` — per-strategy registry mapping a primary's `clientOrderId` → tiers + closeWatchIds. Populated by the action compiler at `Signal.Submit` emit time, consumed by the pipeline at the matching `BrokerEvent.OrderFilled`.
- `com.qkt.dsl.compile.DslCompiledStrategy.multiPositionPerSymbolSymbols: Set<String>` — symbols this strategy will stack on. Used by the deploy-time capability gate.
- Compile-time rejection of `STACK_AT` combined with the inline `OCO {...}` option or with the `STACK` pyramiding clause — both would have silently dropped the conditional clauses.

### Runtime

- `com.qkt.dsl.compile.StackEngine` — one per active PRIMARY leg with `STACK_AT` clauses. Owns an `MfeTracker`; on every tick, fires-or-abandons each tier per `mfe >= threshold && elapsed <= within`.
- `com.qkt.dsl.compile.StackOrchestrator` — per-strategy registry of engines. Handles `onPrimaryFilled` (construct), `onTick` (dispatch by symbol), `onPrimaryClosed` / `onPossibleClose` (destroy). Wraps the engine's emit so each stack signal pre-registers its entry/TP/SL ids with the position tracker for leg-aware fill routing.
- `com.qkt.app.TradingPipeline.wireStackOrchestrator` — wires the orchestrator per DSL strategy: subscribes to `TickEvent` and `BrokerEvent.OrderFilled`, routes fills to either `onPrimaryFilled` (pending stack found) or `onPossibleClose` (close-watch id) and ticks to `onTick`.
- `com.qkt.app.TradingPipeline.requireMultiPositionCapability` — at startup, refuses to deploy a strategy whose `STACK_AT` symbols route to a broker that doesn't declare `MULTI_POSITION_PER_SYMBOL`.

### Position tracker

- `com.qkt.positions.PositionLeg` — `(legId, symbol, side, quantity, entryPrice, openedAt, role, parentLegId?)`. `LegRole ∈ {PRIMARY, STACK}`. STACK requires `parentLegId`.
- `com.qkt.positions.LegBook` — container of legs; enforces single-PRIMARY invariant per symbol. `netView()` derives the legacy singular `Position` so existing readers continue to work.
- `com.qkt.positions.MfeTracker` — high-water-mark of favorable excursion; side-aware. Used both by the stack engine and by the per-primary tracker on `StrategyPositionTracker`.
- `com.qkt.positions.StrategyPositionTracker`:
  - Internal storage migrated from `Map<String, Position>` to `Map<String, LegBook>`.
  - `registerStackOpen(strategyId, clientOrderId, stackLegId, parentLegId)` and `registerStackClose(strategyId, clientOrderId, stackLegId)` — pre-register stack entry / close ids so `applyFill` routes them to `addStackLeg` / `closeLeg` rather than averaging into PRIMARY.
  - `onTick(symbol, price)` — drives per-primary `MfeTracker`s.
  - `primaryMfeFor(strategyId, symbol)` — backs the DSL `POSITION.<stream>.mfe` accessor.
  - `legBookFor(strategyId, symbol)` — direct multi-leg view for reconciliation / testing.

### Broker capability

- `com.qkt.broker.OrderTypeCapability.MULTI_POSITION_PER_SYMBOL` — declared by `PaperBroker` and `MT5Protocol`. Bybit Linear advertises it only in hedge mode; Bybit Spot does not.

### Example

- `examples/hedge-straddle/hedge-straddle.qkt` — pre-existing strategy now carries three `STACK_AT` tiers on each `OCO_ENTRY` leg (matching the pa-quant production shape).

---

## Migration from previous phases

No code-level migration needed for strategies that don't use `STACK_AT` — the `Position`-returning surface is preserved via `LegBook.netView()`. Strategies that *do* opt in:

| Before | After |
|---|---|
| (no equivalent) | `STACK_AT MFE >= <threshold> WITHIN <duration> SIZING <qty> BRACKET { ... }` on a BUY/SELL action |
| (no equivalent) | `POSITION.<stream>.mfe` in expressions |
| `StrategyPositionTracker.applyFill(event)` averaged everything into PRIMARY | Same call, but fills whose `clientOrderId` matches a registered stack entry/close are routed leg-aware. Default path unchanged. |
| `StrategyPositionTracker` had no tick hook | New `onTick(symbol, price)` updates per-primary MFE; `TradingPipeline` already calls this for every `TickEvent` |
| `Broker.capabilities` did not include multi-position semantics | New `MULTI_POSITION_PER_SYMBOL` capability; strategies with `STACK_AT` are rejected at deploy time if the routing broker lacks it |

---

## Usage cookbook

### One stack tier on a plain BUY

The minimum useful shape. Primary opens at market with no bracket; a stack fires when MFE crosses 50 within 30 minutes, with its own SL/TP.

```qkt
STRATEGY one_tier VERSION 1

DEFAULTS { SIZING = 0.1 }

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 0 AND POSITION.btc = 0 THEN BUY btc
        STACK_AT MFE >= 50 WITHIN 30m SIZING 0.05
            BRACKET { STOP LOSS BY 20, TAKE PROFIT BY 100 }
```

### Three-tier hedge-straddle (production shape)

Two opposite-side `OCO_ENTRY` legs; whichever fills attaches three stacks. The full pattern from `examples/hedge-straddle/hedge-straddle.qkt`:

```qkt
THEN OCO_ENTRY {
    BUY  gold ORDER_TYPE = STOP AT gold.close + 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
         TIF GTD NOW + 10m
         STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 20 WITHIN 60m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 30 WITHIN 90m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 },
    SELL gold ORDER_TYPE = STOP AT gold.close - 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
         TIF GTD NOW + 10m
         STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 20 WITHIN 60m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 30 WITHIN 90m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
}
```

### Gating other rules on MFE

`POSITION.<stream>.mfe` is a regular expression — usable in any `WHEN` clause or `LOG`:

```qkt
WHEN POSITION.gold.mfe > 25
THEN LOG "primary up 25 points" mfe=POSITION.gold.mfe duration=POSITION.gold.holding_duration
```

### Constant-folded threshold

The threshold supports arithmetic over literals — handy for pip-vs-price-unit conversions:

```qkt
-- 30 pips on a 5-decimal pair (0.0001 per pip)
STACK_AT MFE >= 30 * 0.0001 WITHIN 15m SIZING 0.05
    BRACKET { STOP LOSS BY 10 * 0.0001, TAKE PROFIT BY 50 * 0.0001 }
```

References, indicators, and `NOW.<field>` in the threshold are rejected at compile time — the per-tick path stays free of expression evaluation.

### Inspecting the leg book in tests

The multi-leg view is observable via `legBookFor`:

```kotlin
val tracker = StrategyPositionTracker()
tracker.applyFill(primaryFill)
tracker.registerStackOpen("alpha", "stack-entry-1", "stack-1", "primary-1")
tracker.applyFill(stackFill)

val book = tracker.legBookFor("alpha", "EURUSD")!!
assertThat(book.primary()!!.role).isEqualTo(LegRole.PRIMARY)
assertThat(book.stacks()).hasSize(1)
assertThat(book.stacks()[0].parentLegId).isEqualTo("primary-1")
```

---

## Testing patterns

- **Stack engine unit tests** (`StackEngineTest`) — drive `onTick` with hand-crafted prices and a `FixedClock`; assert tiers fire on threshold crossings, abandon on window expiry, and that the same tier never fires twice.
- **Tracker stack-routing tests** (`StrategyPositionTrackerStackTest`) — call `registerStackOpen` / `registerStackClose` before `applyFill`; assert the LegBook has a STACK leg with the right `parentLegId`, that PRIMARY entry/qty are untouched, and that close fills realize the correct PnL.
- **Tracker MFE tests** (`StrategyPositionTrackerMfeTest`) — drive `onTick(symbol, price)`; assert `primaryMfeFor` rises monotonically on favorable ticks, stays on unfavorable ones, re-anchors on flip or averaging, returns null after a full close.
- **End-to-end through `TradingPipeline`** (`TradingPipelineStackTest`) — publish a primary `OrderFilled`, publish a `TickEvent` that crosses MFE, then observe both the bracket signal on the bus and the resulting STACK leg in the LegBook.
- **Capability gate** (`TradingPipelineMultiPositionCapabilityTest`) — construct the pipeline with a broker that does/doesn't declare `MULTI_POSITION_PER_SYMBOL`; assert the init throws with the strategy id, symbol, and broker name in the message.

The canonical fakes:
- `PaperBroker` for backtest-like fills.
- `FixedClock` so engine windows and MFE timestamps are deterministic.
- A small `StubDslStrategy` in `TradingPipelineStackTest` for tests that don't need a real DSL compile.

---

## Known limitations

These ship deliberately and are tracked for follow-up phases:

- **Parent-close detection is narrow.** The `closeWatchIds` mechanism only fires when the parent's bracket TP or SL fills via `OrderManager.submitBracketFallback`'s deterministic `${b.id}-tp` / `${b.id}-sl` naming. Plain Market parents (no bracket), native MT5 brackets (broker-assigned ticket ids), and strategy-emitted manual closes are not detected. Engines on those parents keep firing until tiers exhaust by their own fire/abandon semantics. Clean fix needs leg-id correlation on the fill event stream.
- **No retroactive fire.** The engine first evaluates on the tick *after* the primary fill. If the primary fills already past a tier's threshold, the tier fires on the next tick rather than at fill time.
- **`SIZING` for `STACK_AT` is literal lots only.** Risk-fraction, notional, and percent-of-equity sizing on stack tiers will land alongside leg-level risk accounting in a later phase.
- **`BRACKET` for `STACK_AT` is `BY <distance>` only.** `AT`/`PCT`/`RR` forms are rejected — the stack's entry isn't known until fire time, so absolute and ratio-based forms don't translate cleanly.
- **No leg-level realized PnL aggregation per symbol.** `POSITION.<stream>.realized_pnl` still reports strategy-level totals; per-symbol per-leg realized requires lot-level accounting (existing Phase 7d backlog item; not introduced by Phase 27).
- **Engine state is in-memory only.** On a live-session restart the orchestrator starts empty; the position tracker reconciles from broker fills but stack engines do not rebuild.
- **Portfolio child stacks are not consumed by the top-level pipeline.** `PortfolioStrategy.pendingStacks` is an empty stub; children with `STACK_AT` register on their own registries which the runtime doesn't currently inspect. The capability gate *does* aggregate child stack symbols, so deploy-time rejection still works.

---

## References

- Spec: [`docs/superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md`](../superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md)
- Plan: [`docs/superpowers/plans/2026-05-12-phase27-conditional-bracketed-stacks.md`](../superpowers/plans/2026-05-12-phase27-conditional-bracketed-stacks.md)
- DSL reference: [`docs/reference/dsl/stack-at.md`](../reference/dsl/stack-at.md), [`docs/reference/dsl/expressions.md`](../reference/dsl/expressions.md) (`POSITION.<stream>.mfe`)
- Example: `examples/hedge-straddle/hedge-straddle.qkt` and its `README.md`
- Commit range on `phase-27-impl` (rooted at `main`): all commits from `7d8f8d4` (PositionLeg + LegBook scaffolding) through `702a9d8` (this changelog and the DSL reference page). The architectural beats are:
  - `2752778` — action compiler registers PendingStacks for STACK_AT emits
  - `02b6e40` — TradingPipeline wires StackOrchestrator per DSL strategy
  - `8ec1cd1` — StackOrchestrator close detection + compile-time guards
  - `d78f4be` — `MULTI_POSITION_PER_SYMBOL` capability gate
  - `4093cbb` — stack-leg fills routed to the LegBook (Gap #1)
  - `8edf25f` — `POSITION.<stream>.mfe` accessor
