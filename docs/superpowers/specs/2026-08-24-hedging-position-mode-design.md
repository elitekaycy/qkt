# Hedging position mode for simulated brokers — design

**Issue:** #1071. **Status:** approved for implementation.

## Problem

The pipeline is mode-symmetric, but the venue boundary is not. Live MT5 retail accounts
(both production hosts) run in HEDGING margin mode: every entry books its own position
ticket, SL/TP attach to that ticket, and exits close-by-ticket. The backtest tier books
one NET position per (strategy, symbol): `StrategyPositionTracker.applyFillSlice` routes
any fill with no leg registration into `apply()`, which nets into the single PRIMARY leg.
An opposite-direction entry therefore consumes the existing position (REVERSE_TO_* /
INCREASE_* effects) instead of coexisting with it, so backtest and live trade structurally
different books for any strategy that can fire an entry while holding a position.

## What already exists (and is NOT rebuilt)

- `LegBook` / `PositionLeg` with `LegRole.INDEPENDENT`: standalone positions that coexist
  without netting, one venue ticket each (`brokerTicket`), persisted and restorable.
- Registration machinery: `registerIndependentOpen` + `registerStackClose` route an entry
  fill to its own leg and the deterministic `${bracketId}-tp`/`-sl` exits to that leg's
  close — already used for OCO_ENTRY straddles and stack layers.
- Per-leg realized PnL (`applyStackClose`, `applyOwnedLegByTicket`) feeding
  `netStrategyAccountRealized`, strategy PnL, trade history, risk state.
- Per-leg unrealized marks (`StrategyPnL.unrealizedFor` sums legs, so a flat-net hedged
  pair's locked spread is visible to equity/drawdown halts).
- DSL semantics: `POSITION.x` = signed net across legs, `.count`/`.longs`/`.shorts`/
  `.gross` already leg-aware. Unchanged by this design.
- Live close-by-ticket (`closesTicket`/`closesLegId`) and hedging-aware reconcile.

## Design

### 1. One mode knob, venue-derived

`PositionMode { NETTING, HEDGING }` declared where execution simulation is configured:

- CLI: `--position-mode netting|hedging` on `backtest`/`sweep`; scenario file key
  `position_mode`. Default **hedging** for `--broker mt5-sim` and `--broker paper`
  (matching the production venue model); `netting` remains selectable for venues that
  truly net (futures-style).
- `MT5BrokerProfile.expectedMarginMode` (sibling of `expectedTradeMode`): asserted by
  `MT5AccountVerifier` against the account's `marginMode` at connect, and the source of
  truth when replaying a live config.

### 2. Entry routing (the core change)

In HEDGING mode `TradingPipeline` pre-registers every entry the way OCO_ENTRY legs are
registered today:

- `OrderRequest.Bracket` → `registerIndependentOpen(strategyId, entry.id, bracket.id)` +
  `registerStackClose` for `${bracket.id}-tp` / `-sl`.
- Plain entry `Market`/`Limit`/`Stop` (no `closesTicket`/`closesLegId`) →
  `registerIndependentOpen(strategyId, request.id, request.id)`. On a hedging venue a raw
  market SELL opens a short ticket — that is the faithful semantic.
- Closes are unchanged: `CLOSE` already emits `closesLegId` markets per leg.

`applyFillSlice` then never falls through to netting for these fills; each entry opens an
INDEPENDENT leg with `brokerTicket = brokerOrderId` (the sims already publish a unique
per-order id there), and each exit realizes its own leg.

### 3. Simulated broker declarations

- `MT5BrokerSimulator` gains `positionMode` and reports `PositionAccountingMode.HEDGING`
  (today: UNKNOWN, despite advertising MULTI_POSITION_PER_SYMBOL).
- `PaperBroker` gains the same knob (today: hardcoded NETTING). The screening tier grades
  the same book model as the exact tier.

Bracket exits remain resting `-sl`/`-tp` orders in both sims; with leg linkage they are
structurally reduce-only (each closes exactly its own leg's quantity).

### 4. Interplay with #1070 (sweep + reduce-only tripwire) — critical

Both are NET-based and would false-fire under hedging: a short leg's BUY stop while the
book is net long is a *legitimate* exit there. Rule: **an exit with leg linkage is exempt;
an exit without linkage is subject to both.** `OrderManager` gets an optional
`hasLegLinkage(strategyId, clientOrderId)` predicate (backed by the tracker's registered
close map). In netting mode nothing is linked → current behavior, unchanged tests. In
hedging mode every exit is linked → sweep and tripwire stand down for them, and the leg
routing itself provides the reduce-only guarantee. Unlinked strays (should not exist)
remain covered.

### 5. Reporting: positionEffect stays truthful

`positionEffect` is derived from net before/after and would mislabel a hedged short-leg
open while net-long as CLOSE_LONG. `TradeRecord`/`FillState` gain optional `legId` and
`legAction (OPENED|CLOSED)` populated from the tracker's routing decision;
`TradeAuditSummary` prefers leg-aware labels (OPEN_/CLOSE_ + leg side) when present and
keeps today's net labels otherwise. trades.csv appends `legId,legAction` columns.

### 6. Account-level book: documented approximation

The account `PositionTracker` keeps netting in both modes. Under hedging this shifts the
realized/unrealized *split* earlier than the venue would (account cash realizes when
tickets close), but total equity — realized + unrealized — is identical at every mark, so
drawdown/daily-loss/margin evaluation is unaffected. Documented as a deliberate
approximation; per-leg account books are out of scope until something needs them.

## Out of scope

- FIFO partial-consumption resize of netting-mode exits (residual of #1069's scope).
- Account-level leg books (see §6).
- Netting-mode gate defaults in qkt-forge (a forge config change, separate repo).

## Consequences

- Every historical backtest verdict for reversal-capable strategies was earned under
  netting and needs re-earning under hedging — tracked with the forge reseed.
- Parity: backtest and live now share the venue position model; the divergence catalog
  entry for netting-vs-hedging is closed by construction when mode is venue-derived.

## References

Architecture map (2026-08-24, session artifact): tracker routing
`StrategyPositionTracker.kt:161-215`, netting fallthrough `:202-214`; OCO_ENTRY precedent
`TradingPipeline.kt:1068-1087`; sim statelessness `PaperBroker.kt:56`,
`MT5BrokerSimulator.kt:94`; per-leg PnL `StrategyPnL.kt:66-104`. Related: #1069, #617.
