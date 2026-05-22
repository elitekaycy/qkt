# OCO restart recovery — design

**Date:** 2026-05-22
**Status:** approved (brainstorming)
**Issue:** #46

## Context

An OCO ("one-cancels-other") order is two linked legs: when one fills, the
other must auto-cancel; when one is rejected, the other must unwind.
`OrderManager` tracks that linkage in an in-memory `siblings` map, populated by
`submitOco`. On a fill, `onFilled` does
`siblings[filledLeg]?.forEach { cancel(it) }`.

That map is never restored on restart. Investigation found the gap is wider
than the `siblings` map alone: `OrderManager.persistAll()` *writes* pending
orders and the linkage (the latter lossily, encoded as `BracketPair`s) to the
persistor on every state change, but **nothing reads any of it back**.
`loadPendingOrders` and `loadBracketPairs` have zero callers; `OrderManager`'s
`init` only wires bus subscriptions. The persistence is write-only.

So on a daemon restart the new `OrderManager` starts completely empty — every
pending order and the entire OCO linkage are lost. `MT5StateRecovery` recovers
open *positions*; it never touches `OrderManager`'s order state.

## The failure this fixes

While the daemon is down, a pending OCO leg can:

1. **Stay pending** (both legs) — harmless; restoring the legs + linkage makes
   cancel-on-fill work going forward.
2. **Fill, while its sibling stays pending** — *the bug.* The fill should have
   cancelled the sibling; the daemon was down, so it did not. The sibling is
   still a live pending order. When it later triggers too, the position count
   is two — a hedge that became a directional double-bet. This is the
   one-legged-straddle failure #46 exists to fix.
3. **Be cancelled on the venue** — mild; the OCO degrades to a lone pending
   order, no double-position risk.

Scenario 2 is the one that matters. A design that only restores the linkage and
relies on a future `OrderFilled` event does **not** fix it: a fill that happened
during downtime is in the past — on restart the venue shows an already-open
position, not a fresh fill, and `MT5StateRecovery` reconciles it as a
`PositionReconciled`, never an `OrderFilled`. `OrderManager`'s cancel-on-fill is
event-driven on `OrderFilled`, so it never triggers. The fix must actively
reconcile against venue truth, not wait for an event that already passed.

## Goal

On daemon restart, `OrderManager` restores its live OCO legs and their sibling
linkage from the persistor, and proactively cancels any sibling whose pair
filled while the daemon was down.

## Non-goals

- Restoring non-OCO order state (stacks, brackets) on restart — out of scope;
  this spec covers pending orders + OCO linkage only.
- A venue pending-order query on the mt5-gateway — not needed (see §3); a
  gateway change is out of scope.
- Removing the now-redundant lossy siblings→`BracketPair` encoding in
  `persistAll()` — unused, left as-is.

## Design

### 1 · Persist the sibling linkage directly

Add a first-class pair to `StatePersistor`:

```
fun saveSiblings(strategyId: String, siblings: Map<String, List<String>>)
fun loadSiblings(strategyId: String): Map<String, List<String>>
```

Implementations:
- `NoopStatePersistor` — save is a no-op, load returns `emptyMap()`.
- `FileStatePersistor` — a schema-versioned `siblings-<strategyId>.json`, the
  same pattern as the existing `pending-orders` / `bracket-pairs` files.
- `AsyncStatePersistor` — delegate both.

`OrderManager.persistAll()` calls `saveSiblings` alongside its existing
`savePendingOrders` / `saveBracketPairs`. The `siblings` map is global, keyed by
order id; it is partitioned per strategy by each order's `strategyId` before
saving.

### 2 · `OrderManager` restore path

A new method:

```
fun restore(strategyIds: List<String>)
```

For each strategy id:
- `loadPendingOrders(sid)` → for each persisted `(orderId, OrderRequest)`,
  rebuild a `ManagedOrder` in `orders` in state `PENDING` — the order is still
  live on the venue, so it is tracked, not re-submitted.
- `loadSiblings(sid)` → merge into the `siblings` map.

`restore` is called once at session startup, alongside `LiveSession`'s existing
restart-recovery step (`reconcileOrPreload`), before the engine takes ticks.

### 3 · Downtime-fill reconciliation

After `restore`, `OrderManager` subscribes to `BrokerEvent.PositionReconciled`
events with `reason = "startup-recovery"` — the event `MT5StateRecovery` emits
for each open position it recovers. For each such event, `OrderManager` matches
the reconciled `(symbol, side)` — side derived from the sign of `newQty` —
against the restored pending OCO legs. A match means that leg filled while the
daemon was down, so `OrderManager` immediately cancels its sibling.

For a straddle — two opposite-side legs on one symbol — the `(symbol, side)`
match is unambiguous: a recovered position on side X means the side-X leg
filled, so the side-Y leg is cancelled. No venue pending-order query is needed;
the position list `MT5StateRecovery` already fetches is sufficient.

### 4 · Error handling

- `restore` is best-effort: a persistor read failure logs a warning and leaves
  `orders` / `siblings` empty (today's behaviour) — it never blocks startup.
- A restored leg with no live venue counterpart is harmless — it sits in
  `orders` until reconciled or cancelled.
- Cancelling an already-terminal sibling is a no-op — the existing `cancel`
  guard on terminal state covers it.

## Testing

- **Persistence round-trip** — `saveSiblings` then `loadSiblings` via
  `FileStatePersistor` in a temp dir returns the same map.
- **`OrderManager.restore`** — persist pending OCO legs + siblings, construct a
  fresh `OrderManager`, call `restore`, assert `orders` and `siblings` are
  rebuilt.
- **Downtime-fill** — restore an OCO pair, publish a `startup-recovery`
  `PositionReconciled` matching leg A, assert leg B is cancelled.

Real types throughout — `FileStatePersistor` on a `@TempDir`, a `LogBroker`, an
in-process `EventBus`. No mocks.

## Known limitations

- The `(symbol, side)` downtime-fill match is unambiguous for one OCO per
  symbol/side — the hedge-straddle case. Multiple OCO groups on the same symbol
  and side cannot be told apart; documented, and not a double-position risk for
  hedge-straddle.
- A leg *cancelled* (not filled) on the venue during downtime cannot be
  distinguished from "still pending" without a venue pending-order query — a
  gateway addition, left as a follow-up. It carries no double-position risk
  (scenario 3).

## Risks

- `restore` runs before the engine takes ticks; if a restored leg's symbol has
  no market source the leg simply waits — no different from a normally pending
  order.
- The reconciliation cancels a pending order during startup. Cancelling is
  idempotent and guarded; an erroneous cancel of a still-wanted leg would, at
  worst, drop one side of an OCO — strictly safer than leaving a double bet.
