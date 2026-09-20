# Order management

`OrderManager` (in `com.qkt.app`) is the one place orders live between a strategy's signal
and the broker's fill. It is a small facade; the work is done by focused classes in
`com.qkt.app.order`, each named for the one thing it does. This page is the map.

The same classes run in backtest and live — the order manager is part of the shared
`TradingPipeline`, so every rule here applies identically in both modes.

## State: what is known about orders

| Class | Holds |
|---|---|
| `OrderBook` | Every managed order, plus the per-symbol and GTD indexes the tick scan uses |
| `PendingExposureBook` | Exposure live entry orders would add if they filled, for pre-trade risk |
| `ManagedStopBook` | Moving state of engine-held stops: high-water mark, arm flag, step, stop level |
| `SiblingLinks` | One-cancels-other links: OCO legs, bracket stop/target pairs, stack exits |
| `PendingChildBook` | Children of a composite that wait, unarmed, for their parent to fill |
| `BracketBook` | Pre-fill brackets, fill-anchored brackets, restored attached entries |
| `ScaleOutBook` | Scale-out wrappers from submit to their last exit |
| `EngineHeldCloseTickets` | Engine-held stops that close one specific venue position by ticket |
| `OrderStore` | All of the above, plus `track`/`update` — the only code that changes an order record |

## Flow: from request to venue

```
submit(request)
  └─ OrderRouter            plan leg intent, validate, track, then route by shape and venue:
       ├─ VenueSubmission   native order → broker (or a precise local rejection)
       ├─ engine-held       stops/trailing the venue cannot hold rest as PENDING monitors
       ├─ BracketSubmission attached / native / decomposed into an OTO + exit OCO
       ├─ OcoSequencer      place leg1, then leg2 only after leg1 is accepted
       ├─ ScaleOutTracker   basis first; exits armed from the fill (ScaleOutExits)
       ├─ TimeExits         target plus a deadline watched per tick
       └─ StackExecution    seed layer; its fill anchors the rest (StackLayerOrders/Exits)
```

## Reactions: what happens next

| Trigger | Handled by |
|---|---|
| Broker accept / reject / partial / cancel | `OrderEventHandlers` |
| Broker fill | `FillHandler` → `BracketFills`, `AttachedBracketCompletion`, `SiblingCancellation`, `OcoExecutionGuard`, `ProtectiveExitGuard` |
| Tick | `TickEvaluation` → `ManagedStopTicker` (trail/step/tighten), GTD and deadline sweeps, `TriggerFiring` |
| Position SL/TP modify result | `VenuePositionProtection` (falls back to an engine-held stop on refusal) |
| Session restart | `OrderRestorer` → `CompositeRestore`, `EngineHeldRestore`, `VenueRecovery` |
| Risk halt | `OrderCancellation` (halt-survival rules), `HaltCancellations` (retry until confirmed) |

## Invariants worth knowing

- **Terminal outcomes are immutable.** `OrderStore.update` refuses to move an order out of a
  terminal state.
- **One writer per record.** Workflows never write the book directly for state changes; they
  call `track`/`update` through `OrderOps`, so every change is persisted and indexed.
- **Hot path is keyed by symbol.** `TickEvaluation` touches only the tick's symbol's live orders
  and reuses scratch buffers, so steady-state per-tick allocation is zero.
- **Logs keep one name.** Every class here logs under `OrderManager`'s logger, so operator log
  routing and filters do not depend on the internal structure.
