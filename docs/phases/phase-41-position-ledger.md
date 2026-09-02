# Phase 41 — Position Ledger

**Status:** Shipped in three stages to `dev` (A: intent in the type; B: the maps deleted; C: one ledger, derived P&L).
**Spec:** [`../superpowers/specs/2026-09-02-position-ledger-design.md`](../superpowers/specs/2026-09-02-position-ledger-design.md)
**Plan:** [`../superpowers/plans/2026-09-02-position-ledger.md`](../superpowers/plans/2026-09-02-position-ledger.md)

## Summary

Position truth had four writers: the strategy leg books, the account `PositionTracker`, and two
P&L accumulators each keeping its own realized counter, coordinated by pending-order maps that the
fill handler consulted to guess what an execution meant. Phase 41 makes the meaning of an
execution a property of the order (`LegIntent`), makes the strategy ledger the only writer of
position state, derives the account book from it, and turns realized P&L into a fold over one
accounted-event stream. Netting and hedging venues, partial fills, restart recovery, financing and
boot reconciles all go through the same path.

## What's new

- `LegIntent` (`com.qkt.execution`) — `Open(legId, role, parentLegId)`, `Close(legId?, ticket?, partial)`,
  `Net`, `Unplanned`. Carried by every leaf `OrderRequest`; planned once by `LegIntentPlanner` at
  emit and stamped on every leaf `OrderManager` mints; persisted and in evidence.
- `LegIntentResolver` — at fill time: the order's intent (an opposite-side execution under an
  `Open` is a venue close of that leg) → the leg owning the venue ticket (any role on hedging
  venues; non-PRIMARY on netting venues, where a reversal keeps its ticket) → the venue default.
- `StrategyPositionTracker.applyFillDetailed(fill, intent, cumulativeFilled)` — exhaustive over the
  intent. One leg per venue ticket; re-reports book only the cumulative delta; an unknown close
  books nothing and the pipeline publishes nothing for it. The pending maps are gone.
- `AccountPositionView : LegExposureProvider` — the account book as an incrementally maintained
  index over the ledger. `PositionTracker` is deleted. Account unrealized is per leg.
- `FillAccountedEvent.kind` (`EXECUTION`, `FINANCING`, `RECONCILE`) and `executedAt`. The pipeline's
  `bookExecution` builds the event; `foldAccounted` (first subscriber) is the only writer of
  `PnLCalculator`, `StrategyPnL`, `TradeHistory`, the pacer ledger, the runaway breaker, the daily
  tracker and halt evaluation. `applyFinancing` and `applyReconciledRealized` publish the same event.
- `LegFlattener` — halt-flatten closes each ledger leg with `Close(legId, ticket)`.
- `Broker.watchBookedLegs` / `BookedLeg` — the session hands brokers a live view of ticketed ledger
  legs; `MT5PositionPoller` books the missed venue close for a leg whose ticket is absent from two
  consecutive clean snapshots (#1097).
- `Broker.recoverPendingOrders(orders, bookedTickets)` — MT5 joins already-booked tickets without
  republishing their executions (#1096); `matchesOrderComment` never matches across a digit boundary.
- CLI: `--position-mode` accepted on research verbs.

## Migration

| Before | After | Notes |
| --- | --- | --- |
| `PositionTracker()` per session | `strategyPositions.account` | Same `PositionProvider` interface. |
| `strategyPositions.register*` / `forgetPending` | intent on the order | Tests route through `IntentBook` (test helper over the production resolver). |
| `applyFillSlice(fill)` | `applyFillDetailed(fill, intent, cumulativeFilled)` | Exhaustive `when`. |
| `strategyPnL.recordRealized` at boot | `pipeline.applyReconciledRealized` | `kind = RECONCILE`; lifetime realized, not today's budget. |
| `LegIntentResolver(ownedLegByTicket = …)` | `legByTicket = …` | The resolver applies the role rule. |

## Proof

Byte-identical backtest fixtures and golden replays against the pre-refactor base for every
stage (`kind`/`executedAt` are the only new audit keys), the local Exness live wave (canary bracket,
replay parity, four-case parity suite, Insights attribution), the paper-soak attestation, then
bot2 and bot1.
