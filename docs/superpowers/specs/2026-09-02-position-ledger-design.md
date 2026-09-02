# Position ledger — one writer for positions and P&L

**Status:** approved for implementation. **Supersedes** §6 of
`2026-08-24-hedging-position-mode-design.md` (the account-level netting approximation).

## Problem

One fill is booked twice, its meaning is decided out of band, and its P&L is stored twice.

1. **Two writers of the same derived quantity.** `TradingPipeline`'s `OrderFilled` handler applies
   every fill to the account `PositionTracker` (net-averaging) *and* to `StrategyPositionTracker`
   (per-leg), computes realized P&L twice, and records it into two accumulators
   (`PnLCalculator.realizedTotal`, `StrategyPnL.realizedByStrategy`) that are persisted separately
   (`PersistedRiskState.globalRealizedTotal`, `PersistedPnl`). Same fill, two numbers, two files
   that must agree.
2. **A fill's meaning lives in transient side maps.** Before submit, `registerOcoEntryLegs` /
   `registerHedgingEntryLegs` / `registerLegClose` / the stack orchestrator populate three
   `ConcurrentHashMap`s keyed `strategyId|clientOrderId`. `applyFillSlice` consumes them in a fixed
   precedence — stack-open, stack-close, independent-open, venue-ticket guess, then **net into
   PRIMARY**. That last fallback runs on HEDGING venues too whenever a registration is missing
   (restart, cancel/reject race, an order shape nobody registered), which is precisely the wrong
   answer for a hedging account. Close intent is already half in the type
   (`Market.closesTicket/closesLegId`); open intent is not.
3. **The read side disagrees with itself.** `StrategyPnL.unrealizedFor` sums per leg;
   `unrealizedTotalFor` keys off the net view. Live flatten-on-halt sizes off the account net and
   refuses non-NETTING venues; backtest flattens leg-by-leg. `brokerOrderId` is an MT5 ticket, a
   client order id (paper/sim), or a venue order id (Bybit) — the ticket fallback only works on MT5.

## Principle

**A fill is a ledger entry against a leg. The order carries its own intent. There is exactly one
book, and every position and P&L number is a projection of it.**

## Design

### 1. `LegIntent` rides on the order

```kotlin
sealed interface LegIntent {
    /** Open (or extend) leg [legId] with [role]; STACK legs name their [parentLegId]. */
    data class Open(val legId: String, val role: LegRole, val parentLegId: String? = null) : LegIntent
    /** Close (or reduce) one specific leg — by qkt id, by venue ticket, or both. */
    data class Close(val legId: String?, val ticket: String?, val partial: Boolean) : LegIntent
    /** Net against the single PRIMARY — legal only where the venue nets. */
    data object Net : LegIntent
    /** Not decided yet — the planner rejects a leaf submitted in this state. */
    data object Unplanned : LegIntent
}
```

`OrderRequest` gains `val legIntent: LegIntent get() = LegIntent.Unplanned`; every **leaf** variant
(Market, Limit, Stop, StopLimit, IfTouched, TrailingStop, TrailingStopLimit, ArmedTrailingStop,
SteppedStop, TimeTighteningStop) overrides it with a trailing, defaulted constructor parameter so
positional call sites keep binding. Composites keep the default; `OrderManager` stamps the leaves it
mints: a Bracket's entry gets `Open(legId = bracket.id, role)`, its `-tp`/`-sl` get
`Close(legId = bracket.id)`; a stack tier's entry gets `Open(legId, STACK, parentLegId)`; a
scale-out slice gets `Close(ticket, partial = true)`. `withStrategyId`/`withExpiresAt`/
`scaleQuantity` carry the field.

`Market.closesTicket/closesLegId/partialClose` and `IfTouched.closesTicket/partialClose` become
derived views over `legIntent` (kept as properties so the MT5 close-by-ticket path, `RiskReducing`,
`ExitHookManager` and the evidence/persistence payloads are untouched in stage A, removed in stage B).

### 2. One planner decides intent, once

`LegIntentPlanner.plan(request, mode: PositionAccountingMode): OrderRequest` runs in
`TradingPipeline` at the point the three `register*` calls run today, and is the **only** place
accounting mode influences booking:

| request | NETTING / UNKNOWN | HEDGING |
|---|---|---|
| plain entry leaf (Market/Limit/Stop, no close fields) | `Net` | `Open(request.id, INDEPENDENT)` |
| leaf with `closesLegId`/`closesTicket` | `Close(...)` | `Close(...)` |
| Bracket | entry `Net`; minted exits `Net` | entry `Open(bracket.id, INDEPENDENT)`; minted exits `Close(bracket.id)` |
| StandaloneOCO of two Brackets (straddle) | each leg `Open(leg.id, INDEPENDENT)` | same |
| STACK_AT tier (stamped by the orchestrator) | `Open(tier.id, STACK, parent)` / `Close(tier.id)` | same |
| pyramiding `Stack` layer (minted by OrderManager) | `Net` | `Open(layer.id, INDEPENDENT)` |
| already planned | unchanged | unchanged |

UNKNOWN keeps the netting book in the engine (the venue has not confirmed it holds coexisting
tickets; reconciliation stays conservative per direction). Live sessions always resolve a real mode
from the venue. A leaf reaching the broker as `Unplanned` is a programming error and is rejected by
`OrderManager.submit` with a `RiskRejectedEvent`-style loud failure (stage B), never silently netted.

The three constructors that carry no intent today are fixed at the source: `Signal.Buy/Sell →
Market` (`OrderFactory`) leaves `Unplanned` for the planner; `OrderManager`'s TimeExit
`CLOSE_AT_MARKET` and TrailingStop→Market conversions stamp `Close` from the managed order they
close. `MT5Broker.convertAlreadyCrossedStop` copies `legIntent`.

### 3. Resolving intent at fill time

`OrderFilled` is not changed (32 producers). The pipeline resolves intent with a fixed, documented
precedence in `LegIntentResolver`:

1. `orderManager.getOrder(clientOrderId)?.request?.legIntent` — the normal path, including after
   restart (pending orders are persisted with their intent; `recoverPendingOrders` restores them).
2. A leg owned by this strategy whose `brokerTicket == brokerOrderId` — the venue-detected close
   path (`MT5PositionPoller`, `updatesOrderExecution = false`).
3. `Net` **only if** `mode(symbol) != HEDGING`. On HEDGING an unresolvable fill is booked as
   `Open(clientOrderId, INDEPENDENT)` (it *is* a new ticket on such a venue) and raised as a WARN
   plus `NotificationEvent` — the book never silently nets on a hedging account.

Partial fills use the same resolution; intent is a property of the order, so nothing is consumed or
forgotten on terminal/cancel/reject.

### 4. One ledger

The ledger is `StrategyPositionTracker` (the name stays: 40+ call sites, no information in a
rename) holding one `LegBook` per (strategy, symbol). Its single fill mutator:

```kotlin
fun applyFillDetailed(fill: OrderFilled, intent: LegIntent, cumulativeFilled: BigDecimal?): FillApplication
data class FillApplication(val realized: BigDecimal, val legId: String?, val legAction: LegAction?, val unbooked: Boolean)
```

`when (intent)` is exhaustive: `Open` → open or extend the owned leg (one leg per venue ticket; a
re-report on an owned ticket books only the cumulative delta); `Close` → close/reduce that leg and
realize `closedQty × priceDiff`; `Net` → net into PRIMARY. A close naming a leg the book does not
hold books nothing (`unbooked`) and the pipeline accounts and publishes nothing for it. The pending
maps, `register*`, `forgetPending`, `hasRegisteredClose` and `applyOwnedLegByTicket` are gone;
`OrderManager.isLegLinked` reads `getOrder(id)?.request?.legIntent is Close`.

The account book is not a writer. `AccountPositionView : LegExposureProvider` reads an index the
ledger maintains incrementally (`accountBySymbol`, rebuilt for one symbol on every mutation of that
symbol), so `RiskEngine`, the broker constructors, state recovery and the account report columns keep
their `PositionProvider` interface at O(1) per read. `PositionReconciled` → `reconcileNet` on the
ledger. `PositionTracker` is deleted.

### 5. P&L is a fold, not a counter

The fill handler is: resolve intent → `applyFillDetailed` → **one** accounting step
(`bookExecution`: contract size, FX via `AccountingEngine`, modeled commission, venue costs) →
publish `FillAccountedEvent` → exit hooks → trade event and callbacks. The event gained `kind`
(`EXECUTION`, `FINANCING`, `RECONCILE`) and `executedAt` (venue time; `timestamp` stays the bus
stamp). Then:

- `foldAccounted`, a `subscribeFirst` on `FillAccountedEvent`, is the only writer of
  `PnLCalculator`, `StrategyPnL`, `TradeHistory`, the pacer ledger, the runaway breaker,
  `riskState.onFill` and halt evaluation. The accumulators keep their read APIs; no consumer moved.
- The two non-execution realized sources publish the same event: financing accrual
  (`applyFinancing`, `kind = FINANCING`) and the boot reconcile of venue OUT-deals
  (`applyReconciledRealized`, `kind = RECONCILE`, booked once the pipeline exists). A reconcile is
  venue history from before the session: it enters lifetime realized and both accumulators, not the
  daily loss budget. Restore stays the fold's initial seed.
- Ordering: halts see the amount before any later subscriber of the accounted event, and before the
  exit hooks and trade event that follow the publish — the same order as before, now enforced by
  subscription rather than by statement order.
- Audit journal and Insights payloads carry `kind` and `executedAt`, so a financing or reconcile
  amount is visible as its own accounted record.

### 6. Unrealized, flatten, venue truth

- Account unrealized is per leg (`PnLCalculator.unrealizedFor` walks `forEachLeg`); strategy
  unrealized already summed every non-empty book.
- Flatten is one implementation, `LegFlattener`: each ledger leg closes with its own market order
  carrying `Close(legId, ticket)`; a venue ticket attributed to the strategy but unknown to the ledger
  closes by ticket. The netting-only guard remains for venues without position tickets.
- Venue truth keeps the ledger honest (#1097): the session hands the broker a live view of the
  ledger's ticketed legs (`Broker.watchBookedLegs`); the MT5 position poller books the venue close,
  priced from deal history, for any leg whose ticket is absent from two consecutive clean snapshots
  and that it never saw open. The close flows through the ordinary fill path, so the ledger, both
  accumulators and the audit trail treat it like any venue-detected close.
- `positionFor` / `positionsFor` / `allByStrategy` keep returning `Position` from `netView()`.

## Decisions (each pinned by a characterization test before the code moves)

| # | Question | Decision | Why |
|---|---|---|---|
| D1 | Realized: net-average or per-leg? | per-leg; account realized = Σ strategies | hedging needs it; the strategy number already drives halts, sizing, reports |
| D2 | Unrealized total keyed off net view or legs? | legs | a hedged pair's locked loss must reach equity/drawdown/daily-loss halts |
| D3 | Flatten: account net or leg-by-leg? | leg-by-leg, both modes | on hedging an opposite market opens a counter; leg closes are reduce-only by construction |
| D4 | Match fills by `brokerOrderId`? | never as a guess; only `Close(ticket)` from intent or an owned leg's `brokerTicket` | the field means three things across brokers |
| D5 | Where do financing / boot-deals / restore go? | through `FillAccountedEvent` | one ordering, one persistence checkpoint, one audit trail |

## Migration — three stages, each byte-identical and shippable

Acceptance bar per stage: `./gradlew build` green; every parity test in
`src/test/kotlin/com/qkt/parity/` green; the MT5 golden replays
(`compare-golden-replay.sh`) and the repo backtest fixtures hash identical (trade tape, equity
curve, report) against the stage's base commit; live proof on bot2 before bot1.

- **Stage A — intent in the type.** `LegIntent`, planner, resolver; `register*` become thin adapters
  that *derive* the maps from `legIntent`; `applyFillSlice` unchanged. Fixes the three intent-less
  constructors. DTO/evidence schemas gain a defaulted field (no version bump). Zero behavior change.
- **Stage B — delete the maps.** `applyFillSlice` reads the resolver; pending maps, `forgetPending`,
  `hasRegisteredClose`, `applyOwnedLegByTicket` removed; `hasLegLinkage` reads intent. Behavior
  change is exactly the D4/§3-step-3 rule (hedging fills never net) — pinned by a new test, and
  unreachable on the golden tapes.
- **Stage C — one ledger, derived P&L.** `AccountPositionView` fold replaces the account tracker as
  writer; accounting step + `FillAccountedEvent` fold replace the two accumulators; D1–D3, D5 land.
  Reports keep both column pairs (account = fold).

## Out of scope

- FIFO partial consumption for netting exits (still #1069 residual).
- Changing `BrokerEvent.OrderFilled` or any broker adapter's fill publication.
- Per-strategy account currency; `AccountingEngine` is unchanged.

## Risk

High — live-money correctness, persisted-state format, 43+38 test files. Mitigated by the three
byte-identical stages, characterization tests for D1–D5 before any code moves, and the existing
parity/golden harness as the gate.

## References

Blast-radius inventory (2026-09-02 session artifact; every call site with file:line). Fill handler
`app/TradingPipeline.kt:513-658, 660-792`; intent registration `:387-389, 1083-1143, 1169-1189`;
resolver precedence today `positions/StrategyPositionTracker.kt:209-258`; net view choke point
`:614-630`; close-intent fields `execution/OrderRequest.kt:60-79, 161-164`; whole-hierarchy `when`s
`:462, 515, 531, 570`; positional constructors `dsl/compile/OrderTypeCompiler.kt:83-200`,
`LatchCompiler.kt:143-196`; DTO `persistence/FileStatePersistor.kt:728-766`; account accumulator
`pnl/PnLProvider.kt:43-53`; strategy accumulator `pnl/StrategyPnL.kt:29, 53-62`; non-fill realized
`app/TradingPipeline.kt:849-858`, `app/LiveSession.kt:1054-1091`. Related: #1071, #1069, #617.
