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
- [x] `LedgerAccountingCharacterizationTest` pins a netting round trip, a partial slice, a financing
  accrual and a boot reconcile on every consumer: both accumulators, `DailyPnLTracker`,
  `TradeHistory`, `equityFor`/`balanceFor`, the accounted event's fields and the trade events.
  Restart recovery, commission/venue-cost netting and FX conversion stay pinned by their existing
  suites (`StrategyPositionTrackerReplayTest`, `TradingPipelineVenueCostsTest`, `AccountingEngine*`).

### Task C2: `AccountPositionView` fold
**Files:** `positions/AccountPositionView.kt` (new), `positions/PositionProvider.kt`,
`positions/StrategyPositionTracker.kt`, `app/LiveSession.kt`, `research/ReplayEngine.kt`, `app/Main.kt`,
`risk/RiskState.kt`, `app/TradingPipeline.kt`.
- [x] `PositionTracker` deleted. `StrategyPositionTracker.account: LegExposureProvider` reads an
  `accountBySymbol` index the ledger maintains incrementally on every mutation (`reindex(symbol)`), so
  the account book is a derivation, never a second writer. `positions.applyFill/reset` gone;
  `PositionReconciled` → `reconcileNet(symbol, …)` on the ledger. Account unrealized is per leg
  (`PnLCalculator.unrealizedFor` over `forEachLeg`).

### Task C3: accounting step + `FillAccountedEvent` fold
**Files:** `app/TradingPipeline.kt`, `events/Event.kt`, `app/LiveSession.kt`, `app/LegFlattener.kt` (new),
`app/LegIntentResolver.kt`, `broker/Broker.kt`, `broker/CompositeBroker.kt`, `broker/mt5/MT5Broker.kt`,
`broker/mt5/MT5PositionPoller.kt`, `observe/EngineAuditJournal.kt`, `observe/insights/InsightsTranslate.kt`.
- [x] Handler = `bookExecution` (resolve → `applyFillDetailed` → contract size, FX, commission, venue
  costs → one `FillAccountedEvent`) → publish → exit hooks → `finishExecution` (trade event, callbacks).
  The account and strategy realized figures are the same number from the one ledger.
- [x] `foldAccounted` is the only writer of `PnLCalculator`, `StrategyPnL`, `TradeHistory`, the pacer
  ledger, the runaway breaker, `riskState.onFill` and halt evaluation; it is a `subscribeFirst` on the
  accounted event so halts see the amount before any later subscriber. The event carries `kind`
  (EXECUTION / FINANCING / RECONCILE) and `executedAt` (venue time, distinct from the bus stamp).
- [x] `applyFinancing` publishes `kind = FINANCING`; the boot OUT-deal reconcile publishes
  `kind = RECONCILE` through `applyReconciledRealized` once the pipeline exists (lifetime realized and
  both accumulators, not today's loss budget). Audit journal and Insights payloads carry both fields.
- [x] Flatten is one leg-by-leg implementation (`LegFlattener`): every ledger leg closes with
  `Close(legId, ticket)`; a venue ticket attributed to the strategy but unknown to the ledger closes
  by ticket. Replay never had a second flatten path — nothing to unify there.
- [x] #1097: the ledger hands its ticketed legs to the broker (`Broker.watchBookedLegs`); the MT5
  position poller books the missed venue close, from deal history, for any leg whose ticket is absent
  from two consecutive clean snapshots (`MT5PositionPollerCloseTest`). The resolver treats a PRIMARY's
  ticket as one position on hedging venues (so that synthesized close resolves to `Close`) and nets it
  on netting venues (a reversal keeps its ticket).
- [ ] Not moved: `unrealizedTotalFor` already sums every non-empty ledger book (its key set is the
  set of books with legs), so the "iterate ledger symbols" change is a no-op and was not made.
- **Tests:** C1 pins green unchanged; `LedgerAccountingCharacterizationTest` kinds/venue-time;
  `LegIntentResolverTest` PRIMARY-ticket rule; `MT5PositionPollerCloseTest` vanished-leg cases.

### Task C4: docs + catalog
- [x] `docs/parity/backtest-vs-live.md`: 2026-09-02 ledger rows (account book derived; flatten leg-by-leg).
- [x] Phase changelog `docs/phases/phase-41-position-ledger.md`; KDoc on every new public type.

### Task C5: stage gate + live attestation wave (bot2 → bot1).
