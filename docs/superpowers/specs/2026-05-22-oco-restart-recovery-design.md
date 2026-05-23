# OCO restart recovery — design

**Date:** 2026-05-22
**Status:** approved (brainstorming)
**Issue:** #46

## Context

An OCO ("one-cancels-other") order is two linked legs: when one fills, the
other must auto-cancel. `OrderManager` tracks that linkage in an in-memory
`siblings` map, populated by `submitOco`. On a fill, `onFilled` does
`siblings[filledLeg]?.forEach { cancel(it) }`. `MT5Broker` separately tracks
each live pending order by venue ticket in `pendingByTicket`; its pending-order
poller turns a venue fill into the `OrderFilled` event that drives the cancel.

Neither structure survives a restart. On a daemon restart the new `OrderManager`
starts with an empty `siblings` map and the new `MT5Broker` with an empty
`pendingByTicket` — the OCO linkage and the broker's pending-order tracking are
both lost. `MT5StateRecovery` recovers open *positions* from the venue; it never
touches pending orders or OCO linkage.

## The failure this fixes

While the daemon is down — or after a restart, before recovery — a pending OCO
leg can:

1. **Stay pending** (both legs) — restoring the legs + linkage + broker
   tracking makes cancel-on-fill work going forward.
2. **Fill, while its sibling stays pending** — *the bug.* The fill should have
   cancelled the sibling; the daemon was down, so it did not. When the sibling
   later triggers too, the position count is two — a hedge that became a
   directional double-bet. This is the one-legged-straddle failure #46 fixes.
3. **Be cancelled on the venue** — the OCO degrades to a lone pending order, no
   double-position risk.

Scenario 2 is the one that matters, and it has two timing variants: the fill
happens *during* downtime, or *after* restart but before the broker's pending
tracking is rebuilt. Both must be covered.

## What the venue can and cannot tell us

The fix joins persisted qkt state to live venue state. The join key matters:

- **qkt `clientOrderId`s do not survive a venue round-trip.** MT5 truncates
  order comments to 16 chars; a `dsl-hedge_straddle-1` id truncates to
  `dsl-hedge_stradd` — the unique suffix gone. This is why `matchOrphan` is
  best-effort and falls back to `recovered-<ticket>`.
- **The venue ticket is stable.** It is a venue-assigned `Long`, never
  truncated, and — critically — **MT5 preserves the ticket across the
  pending→position transition** (`onPendingPositionOpened` looks up pending meta
  by `position.ticket`). A pending order that fills becomes a position with the
  *same* ticket.

So: persistence carries the OCO **identity** (which is immutable once placed);
the venue carries **liveness** (pending / filled / gone); they join **by
ticket**.

## Goal

On daemon restart, qkt restores its live OCO legs and sibling linkage from the
persistor, re-seeds `MT5Broker`'s pending-order tracking from venue truth, and
cancels any sibling whose pair filled while the daemon was down — reusing the
existing `OrderFilled` → cancel-on-fill path rather than adding a parallel one.

## Non-goals

- Restoring non-OCO order state (stacks, brackets) on restart — out of scope.
- A new mt5-gateway endpoint — not needed: `MT5Client.getPendingOrders` and
  `getPositions` already exist.
- Removing the now-redundant lossy siblings→`BracketPair` encoding in
  `persistAll()` — unused, left as-is.
- Distinguishing a leg *cancelled* on the venue during downtime at restore time
  — the broker's pending poller self-heals it on the next tick (scenario 3, no
  double-position risk).

## Design

### 1 · Persist OCO leg records

Today `persistAll()` writes only bare `OrderRequest`s, and only for orders in
state `PENDING`/`CREATED` — a live OCO leg is `WORKING`, so it is not persisted
at all, and even the persisted form drops the venue ticket. The fix persists a
first-class OCO leg record carrying everything needed to rebuild it.

Add to `StatePersistor`:

```
fun saveOcoLegs(strategyId: String, legs: List<PersistedOcoLeg>)
fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg>
```

`PersistedOcoLeg` holds `clientOrderId`, `brokerOrderId` (the venue ticket),
`strategyId`, the leg's `OrderRequest`, and its `siblingIds`. Implementations:
`NoopStatePersistor` keeps it in memory (consistent with its other methods);
`FileStatePersistor` writes a schema-versioned `oco-legs.json`, reusing the
existing `OrderRequestDto`; `AsyncStatePersistor` delegates.

`OrderManager.persistAll()` iterates its `siblings` map — every key is an OCO
leg — and for each non-terminal leg with a `brokerOrderId`, emits a
`PersistedOcoLeg`, partitioned per strategy.

### 2 · `OrderManager.restore(strategyIds)`

A new method, called once at session startup. For each strategy:

- `loadOcoLegs(sid)` → for each persisted leg, rebuild a `ManagedOrder` in
  `orders` in state **`WORKING`** — the order is live on the venue, so it is
  tracked, never re-submitted. `WORKING` is essential: `evaluateTriggers`
  re-dispatches `PENDING` orders, so restoring as `PENDING` would re-place a
  duplicate on the venue. Restore the `siblings` entries from the same records.
- Hand the rebuilt `ManagedOrder`s to `broker.recoverPendingOrders(legs)` (§3).

Order matters: `orders` and `siblings` are rebuilt *before* the broker call, so
the `OrderFilled` events the broker emits in §3 find their linkage.

### 3 · Broker pending recovery emits the missed fills

Add to `Broker`: `fun recoverPendingOrders(orders: List<ManagedOrder>)`, default
no-op. `MT5Broker` implements it in two passes:

- **Pass 1 — re-seed.** Query `client.getPendingOrders(magic)` and
  `client.getPositions(magic)`. For every recovered leg whose ticket is still in
  the pending set, re-seed `pendingByTicket` / `pendingTickets` so the
  steady-state pending poller and `cancel` work going forward.
- **Pass 2 — emit missed fills.** For every recovered leg whose ticket is now a
  *position* (it filled while the daemon was down), publish
  `BrokerEvent.OrderFilled` with the leg's real `clientOrderId`.

`OrderManager`'s existing `onFilled` handler receives that `OrderFilled`, looks
up `siblings[clientOrderId]`, and cancels the still-pending sibling — the
ordinary cancel-on-fill path, fed the event it missed. No new reconciliation
logic in `OrderManager`. Pass 1 precedes pass 2 so the sibling's broker tracking
is seeded before any `cancel` reaches the broker.

A leg whose ticket is in neither set was cancelled/expired during downtime; it
is left for the pending poller to reconcile (scenario 3).

### 4 · LiveSession wiring & error handling

`LiveSession.start()` calls `orderManager.restore(...)` once at startup, after
the existing `reconcileOrPreload` step, before the engine takes ticks.

- `restore` is best-effort: a persistor or venue read failure logs a warning and
  leaves state empty — it never blocks startup.
- A restored leg with no live venue counterpart is harmless.
- Cancelling an already-terminal sibling is a no-op — the existing `cancel`
  terminal guard covers it.

## Testing

- **Persistence round-trip** — `saveOcoLegs` then `loadOcoLegs` via
  `FileStatePersistor` on a `@TempDir` returns the same records.
- **`OrderManager.restore`** — persist OCO legs, construct a fresh
  `OrderManager`, call `restore`, assert `orders` (state `WORKING`) and
  `siblings` are rebuilt.
- **Broker recovery emits a fill** — a fake/`Log` broker stub whose
  `getPositions` reports one leg's ticket; assert `recoverPendingOrders`
  publishes `OrderFilled` for that leg's `clientOrderId` and not the other.
- **End-to-end downtime fill** — persist an OCO pair, restart, run `restore`
  against a broker reporting leg A as a position; assert leg B ends `CANCELLED`
  via the ordinary cancel-on-fill path.

Real types throughout — `FileStatePersistor` on a `@TempDir`, an in-process
`EventBus`. No mocks; brokers are real implementations or `object : Broker`
stubs returning fixed venue snapshots.

## Known limitations

- Recovery depends on the venue ticket being persisted at placement time. A leg
  that crashed the daemon between venue acceptance and the next `persistAll()`
  is not recovered — a one-tick window, no worse than today.
- Only OCO legs are restored. Stacks and brackets remain unrestored on restart.

## Risks

- `restore` runs before the engine takes ticks; a restored leg whose symbol has
  no market source simply waits — identical to a normally pending order.
- The recovery cancels a pending order during startup. Cancelling is idempotent
  and terminal-guarded; an erroneous cancel of a still-wanted leg would, at
  worst, drop one side of an OCO — strictly safer than leaving a double bet.
- Restoring a leg in the wrong state (`PENDING` instead of `WORKING`) would let
  `evaluateTriggers` re-place it — a duplicate venue order. §2 fixes the state
  explicitly; the restore test asserts `WORKING`.
