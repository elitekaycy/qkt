# Position ledger — implementation plan

> Spec: `docs/superpowers/specs/2026-09-02-position-ledger-design.md`.
> TDD per task; every task ends in a commit. Each stage is its own PR to `dev` and must be
> byte-identical on the parity suite + golden replays before the next stage starts.

## Stage A — intent in the type (zero behavior change)

### Task A1: `LegIntent` + `OrderRequest.legIntent`
**Files:** `execution/LegIntent.kt` (new), `execution/OrderRequest.kt`.
- [x] Sealed `LegIntent { Open(legId, role, parentLegId?), Close(legId?, ticket?, partial), Net, Unplanned }`.
- [x] `OrderRequest.legIntent` default `Unplanned`; trailing defaulted param on every leaf variant;
  `withStrategyId`/`withExpiresAt`/`scaleQuantity` carry it; `withLegIntent(intent)` helper for leaves.
- [x] `Market.closesTicket/closesLegId/partialClose` and `IfTouched.closesTicket/partialClose` stay as
  constructor params; `init` requires them consistent with a `Close` intent when both set.
- **Tests:** `OrderRequestLegIntentTest` — default is Unplanned; copy helpers preserve it; positional
  constructor calls still compile (existing `OrderTypeCompilerTest` untouched).

### Task A2: planner
**Files:** `app/LegIntentPlanner.kt` (new), `app/TradingPipeline.kt`.
- [x] `plan(request, mode)` per spec §2 table; idempotent on already-planned requests.
- [x] Pipeline calls the planner before `registerOcoEntryLegs/registerHedgingEntryLegs/registerLegClose`;
  the three `register*` are rewritten to *derive* their map entries from `legIntent` (adapters).
- **Tests:** `LegIntentPlannerTest` — one row per spec table cell × mode; pipeline ownership tests
  (`TradingPipelineOwnershipTest`) unchanged and green.

### Task A3: OrderManager stamps the leaves it mints
**Files:** `app/OrderManager.kt` (`bracketExitOco`, `submitBracketFallback`, `submitBracketAttached`,
`attachLayerSl/Tp`, scale-out prep, OCO double-fill close, TimeExit close, trigger→Market
conversions), `dsl/compile/StackEngine.kt`.
- [x] Bracket entry → `Open(bracket.id, role from planner)`; `-tp`/`-sl` → `Close(bracket.id)`.
- [x] Stack tier entry → `Open(tier.id, STACK, parent)`; tier exits → `Close(tier.id)`.
- [x] TimeExit `CLOSE_AT_MARKET` and TrailingStop→Market carry `Close` from the managed order.
- **Tests:** extend `OrderManagerAttachedBracketTest`, `TradingPipelineStackTest` to assert intent on
  minted leaves; id-naming assertions unchanged.

### Task A4: persistence, evidence, brokers carry the field
**Files:** `persistence/FileStatePersistor.kt` (DTO + to/fromDomain), `execution/OrderRequestEvidence.kt`,
`broker/mt5/MT5Broker.kt` (`convertAlreadyCrossedStop`), `execution/OrderFactory.kt`.
- [x] `OrderRequestDto.legIntent: LegIntentDto? = null` (no schema bump); round-trip.
- [x] Evidence payload emits `legIntent`; `SCHEMA_VERSION` unchanged (additive key).
- [x] `convertAlreadyCrossedStop` copies `legIntent`.
- **Tests:** persistor round-trip for each leaf; evidence golden update; MT5 convert test.

### Task A5: resolver (wired, not yet authoritative)
**Files:** `app/LegIntentResolver.kt` (new), `app/TradingPipeline.kt`.
- [x] `resolve(fill): LegIntent` with the spec §3 precedence; pipeline computes it and asserts (WARN
  only in stage A) that it agrees with what `applyFillSlice` decided (`FillApplication.legAction`).
- **Tests:** resolver unit tests; a pipeline test that a venue-detected close resolves by ticket.

### Task A6: stage gate
- [x] `./gradlew build`, parity suite, golden replays hash-identical vs base; PR to `dev`.

## Stage B — delete the maps

### Task B1: characterization pins for D4
- [x] Resolver venue default pinned in `LegIntentResolverTest` (HEDGING → INDEPENDENT leg, NETTING/UNKNOWN → net).
- [x] Restart replay pins (#1096) in `StrategyPositionTrackerReplayTest`: a re-report on an owned ticket
  books once; a venue ticket belongs to exactly one leg; a further slice books only the venue's increment.
- [x] Comment-match pin (#1096b) in `Mt5CommentMatchTest`.
- [x] Straddle whipsaw pin (found by the stage-A verifier in `TradingPipelineOcoEntryTest`): when
  leg A fills, the OCO cancels leg B and today's `forgetPending` drops B's registration, so a
  subsequent B fill nets into PRIMARY instead of opening its INDEPENDENT leg. With intent on the
  order the cancel forgets nothing; assert B opens as INDEPENDENT (`legAction == OPENED`,
  role INDEPENDENT) and the book holds two legs with distinct roles, not PRIMARY + INDEPENDENT.

### Task B2: `applyFillSlice` reads the resolver
**Files:** `positions/StrategyPositionTracker.kt`, `app/TradingPipeline.kt`, `app/OrderManager.kt`.
- [x] `applyFillDetailed(fill, intent, cumulativeFilled)` exhaustive `when`; the three maps, `register*`,
  `forgetPending`, `hasRegisteredClose`, `applyOwnedLegByTicket` deleted; `OrderManager.isLegLinked`
  reads the order's intent.
- [x] Ledger rules: one leg per venue ticket; a re-report on an owned ticket books only the cumulative
  delta; a close naming a leg the book does not hold books nothing (`FillApplication.unbooked`) and the
  pipeline accounts/publishes nothing for it.
- [x] Recovery: `Broker.recoverPendingOrders(orders, bookedTickets)` — MT5 joins already-booked tickets
  without republishing their executions; `matchesOrderComment` never matches across a digit boundary.
- [x] Tests rewritten on `IntentBook` (routes through the production resolver).

### Task B3: stage gate (as A6).

## Stage C — one ledger, derived P&L

### Task C1: characterization pins for D1, D2, D3, D5
- [ ] Netting flip, hedged straddle, partial fill, restart recovery, commission + venue cost netting,
  FX conversion, financing accrual, boot OUT-deal reconcile — each asserts today's exact numbers on
  `realizedFor`, `realizedTotal`, `unrealizedFor/TotalFor`, `equityFor`, `balanceFor`,
  `DailyPnLTracker`, `TradeHistory`, and the two flatten paths.

### Task C2: `AccountPositionView` fold
**Files:** `positions/AccountPositionView.kt` (new), `app/LiveSession.kt`, `research/ReplayEngine.kt`,
`app/Main.kt`, `risk/RiskState.kt`, `app/TradingPipeline.kt`.
- [ ] `PositionProvider` over the ledger; replace the four `PositionTracker()` constructions; delete
  `positions.applyFill/reset` calls; `PositionReconciled` → ledger reconcile.
- **Tests:** `AccountPositionViewTest`; report column pairs byte-identical on fixtures.

### Task C3: accounting step + `FillAccountedEvent` fold
**Files:** `app/FillAccounting.kt` (new), `app/TradingPipeline.kt`, `pnl/StrategyPnL.kt`,
`pnl/PnLProvider.kt`, `risk/RiskState.kt`, `pnl/TradeHistory.kt`, `events/Event.kt`.
- [ ] Handler: resolve → `ledger.apply` → account → publish; move `riskState.onFill`/halt evaluation
  to `subscribeFirst<FillAccountedEvent>`; accumulators become subscribers; `applyFinancing` and the
  boot OUT-deal path publish `kind = FINANCING/RECONCILE` events; `PersistedPnl` written by the fold.
- [ ] `unrealizedTotalFor` over ledger symbols; single leg-by-leg flatten shared by live + replay.
- **Tests:** C1 pins green unchanged; ordering test (halt sees fill before OCO sibling cancel).

### Task C4: docs + catalog
- [ ] `docs/parity/backtest-vs-live.md`: account-netting approximation row closed; flatten row closed.
- [ ] Phase changelog `docs/phases/phase-41-position-ledger.md`; KDoc on every new public type.

### Task C5: stage gate + live attestation wave (bot2 → bot1).
