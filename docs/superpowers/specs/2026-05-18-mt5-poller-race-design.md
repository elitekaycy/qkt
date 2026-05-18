# MT5 poller race fix — pending-fills lost when pending-poller wins

> Closes a v0.28.4 follow-up bug: `MT5PendingOrderPoller` and `MT5PositionPoller` are independent threads, so a pending-stop → position transition is observed by them in non-deterministic order. When the pending-poller observes the disappearance first, it phantom-cancels the order and erases the meta the position-poller needs — the eventual `OrderFilled` is never emitted and the strategy silently loses track of its own live position.

## Goal

1. Eliminate the race so a pending → position transition on MT5 always produces exactly one `BrokerEvent.OrderFilled` for the originating qkt order.
2. Make the failure-equivalent path observable: any new venue position the broker cannot correlate to a qkt-side pending must produce a WARN, not a silent return.

## Motivation

Observed in production on 2026-05-18 19:55 UTC. `hedge-straddle` submitted an OCO (BUY_STOP + SELL_STOP). The SELL_STOP was rejected at the gateway. The BUY_STOP was placed pending. 19 minutes later it triggered to a position (ticket `2756533542`, magic `10001`, comment `dsl-hedge_stradd`) — but `qkt status hedge-straddle` continued reporting `positions: []` and `lastTrade` frozen at the previous trade's close. The strategy could not manage its own live position; only MT5's server-side SL/TP would close it.

The v0.28.4 fix (`fix/oco-bracket-phantom-fill`) registered pending-entry Brackets in `pendingByTicket`. That fix is necessary but not sufficient — the meta entry exists, but a poll-ordering race deletes it before the position-poller can read it.

### Trace (ticket 2756533542)

| Event | Source |
|---|---|
| 19:55:00.673 OCO submit | log |
| 19:55:01.112 SELL_STOP rejected (HTTP 400) | log |
| 19:55:01.x BUY_STOP placed pending, `pendingByTicket[ticket]` set | inferred from later state |
| 20:14:35 MT5 fills BUY_STOP — ticket moves from `/orders` to `/positions` atomically | gateway snapshot |
| ~20:14:35-36 pending-poller ticks first: `onPendingDisappeared` removes from `pendingByTicket`, emits phantom `OrderCancelled` (silent — no subscriber logs it) | inferred |
| ~20:14:36 position-poller ticks: `onPendingPositionOpened` finds `pendingByTicket` empty, returns silently | inferred |
| Result | strategy `positions: []`, live MT5 position untracked |

The 14:55 OCO trigger 7 hours earlier on the same instance worked correctly — position-poller happened to win that race. Both outcomes are reachable from the same code path with the same inputs.

## Scope

### In scope

- **Fix A** — `MT5Broker.onPendingDisappeared` confirms the disappearance is a cancel (not a fill) by checking `/positions` before publishing `OrderCancelled`. If the ticket appears in positions, it routes through the same fill path `onPendingPositionOpened` would have taken.
- **Fix B** — `MT5Broker.onPendingPositionOpened` logs a WARN (not a silent return) when a new venue position has no corresponding `pendingByTicket` entry. Carries ticket, symbol, side, magic, and the candidate causes ("opened outside this qkt session OR poll-ordering race lost meta").
- Regression tests covering both poller-orderings of a pending → position transition.

### Out of scope

- Unifying the two pollers into a single broker-state poller. That is a cleaner long-term fix but a larger refactor; tracked as a Phase 32+ architecture task, not v0.28.5.
- The per-strategy log file (`/var/lib/qkt/logs/hedge-straddle.log`) being empty in prod — separate v0.28.5 bug.
- The `SELL_STOP price must be below current bid` gateway rejection seen on tight-offset hours — separate v0.28.5 bug (the OCO has already been submitted by the time MT5 sees it, so a brief bid drift in the ~750 ms placement gap causes the second leg to fail validation).
- The `FileStatePersistor.savePendingOrders: skipping non-persistable variant StandaloneOCO` WARN at every OCO submit — separate backlog item.

## Design

### Fix A — defensive cross-check in `onPendingDisappeared`

The pending-poller calls `MT5Broker.onPendingDisappeared(ticket)` whenever a tracked ticket leaves `/orders`. Today the method's disambiguation relies entirely on `recentlyFilledTickets` having been set by `onPendingPositionOpened`. That ordering is not guaranteed.

The fix: before treating the disappearance as a cancel, ask the venue whether `ticket` is now a position.

```kotlin
private fun onPendingDisappeared(ticket: Long) {
    val meta = pendingByTicket[ticket] ?: return  // unchanged: not ours

    // Disambiguation #1 — recentlyFilledTickets marker from a prior position-poller tick.
    val recentlyFilledAt = recentlyFilledTickets[ticket]
    val ttlMs = profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER
    val now = clock.now()
    if (recentlyFilledAt != null && now - recentlyFilledAt < ttlMs) {
        pendingByTicket.remove(ticket)
        pendingTickets.entries.removeIf { it.value == ticket }
        recentlyFilledTickets.remove(ticket)
        return
    }

    // Disambiguation #2 — cross-check /positions. If the ticket is a position now,
    // the pending-poller observed the transition before the position-poller did;
    // synthesize the fill path here instead of phantom-cancelling.
    val asPosition =
        runCatching { client.getPositions(magic = profile.magic).firstOrNull { it.ticket == ticket } }
            .getOrNull()
    if (asPosition != null) {
        // Drive the same path onPendingPositionOpened would have taken. The position-poller
        // will see this ticket in its lastSnapshot on its next tick and produce no duplicate
        // "opened" event (the snapshot diff goes "absent → present" only once).
        onPendingPositionOpened(asPosition)
        return
    }

    // Real cancel / GTD expiry.
    pendingByTicket.remove(ticket)
    pendingTickets.entries.removeIf { it.value == ticket }
    recentlyFilledTickets.entries.removeIf { now - it.value >= ttlMs }
    bus.publish(
        BrokerEvent.OrderCancelled(
            clientOrderId = meta.orderId,
            brokerOrderId = ticket.toString(),
            reason = "external or gtd-expired (pending disappeared from venue)",
            strategyId = meta.strategyId,
            timestamp = clock.now(),
        ),
    )
}
```

Note the change from `pendingByTicket.remove(ticket) ?: return` to `pendingByTicket[ticket] ?: return`. Today the method removes-then-decides; the new flow needs to decide-then-remove so the position-poller can still find the meta if the cross-check routes to `onPendingPositionOpened`. The original `remove` paths are preserved in each branch that actually terminates the pending lifecycle.

**Failure modes considered:**

| Scenario | Behavior |
|---|---|
| `client.getPositions` throws (gateway down, timeout) | `runCatching` swallows; falls through to the original cancel path. Worst case is one phantom cancel — matches today's behavior, no regression. |
| Position exists but with a different magic | Filter is `magic = profile.magic`, so this is filtered out — proceeds to cancel path, correct. |
| Same ticket appears in both `/orders` and `/positions` for one tick | Cross-check sees it in `/positions` → routes to fill path. Next pending-poller tick will see it now-absent from `/orders` again, but `pendingByTicket` was already cleared by `onPendingPositionOpened`, so the second `onPendingDisappeared` returns at line 1 (`meta = null`). No duplicate fill. |
| Two pendings disappear in the same tick | One `getPositions` call per disappeared ticket. With pollIntervalMs=1000ms and rare simultaneous fills, the extra HTTP cost is negligible. Not worth batching today. |

### Fix B — observability for the no-meta path in `onPendingPositionOpened`

Today the method silently returns when `pendingByTicket` doesn't have the ticket. That is correct behavior for legitimately-external positions (manual MT5 trades, another qkt instance sharing the magic) but it also masks the race-condition bug and any future correlation bugs.

```kotlin
private fun onPendingPositionOpened(position: MT5Position) {
    val meta = pendingByTicket.remove(position.ticket)
    if (meta == null) {
        log.warn(
            "MT5Broker {} saw new position ticket={} symbol={} side={} magic={} with no qkt-side " +
                "pending meta — either externally placed or pending-poller already consumed the " +
                "meta (poll-ordering race; see fix A path).",
            profile.name,
            position.ticket,
            position.symbol,
            if (position.type == 0) "BUY" else "SELL",
            profile.magic,
        )
        return
    }
    pendingTickets.remove(meta.orderId)
    recentlyFilledTickets[position.ticket] = clock.now()
    positionMetaByTicket[position.ticket] = meta
    // … rest unchanged
}
```

After fix A is in place this WARN should be **rare** — it fires only when a position truly was opened outside qkt. If it fires for a ticket the broker recently placed, we have a correlation bug to investigate.

## Testing

### Unit tests (`MT5BrokerTest`)

Use the existing `FakeMT5Client` to simulate both poll-orderings of a fill.

**Test 1 — pending-poller wins the race (the production bug)**
1. Submit pending-entry Bracket. Verify `pendingByTicket[ticket]` populated.
2. `FakeMT5Client.simulateFill(ticket)` — ticket moves from pending list to positions list.
3. Invoke `pendingPoller.tickForTesting()` BEFORE `positionPoller.tick()`.
4. Assert exactly one `OrderFilled` published, zero `OrderCancelled`.
5. Run `positionPoller.tick()`. Assert no duplicate `OrderFilled`.

**Test 2 — position-poller wins the race (the happy path, must still work)**
1. Same setup as test 1.
2. `positionPoller.tick()` BEFORE `pendingPoller.tickForTesting()`.
3. Assert one `OrderFilled` from position-poller.
4. `pendingPoller.tickForTesting()`. Assert no duplicate event (TTL marker consumed).

**Test 3 — real external cancel**
1. Submit pending-entry Bracket.
2. `FakeMT5Client.simulateCancel(ticket)` — ticket removed from pending list, NOT added to positions.
3. `pendingPoller.tickForTesting()`.
4. Assert one `OrderCancelled` with `reason = "external or gtd-expired ..."`.

**Test 4 — observability WARN for un-tracked position**
1. Hand `positionPoller` an `MT5Position` with a ticket NOT in `pendingByTicket`.
2. Capture log output via a test SLF4J appender.
3. Assert a WARN line containing `"no qkt-side pending meta"`.
4. Assert no `OrderFilled` published.

### What we are NOT testing

- Integration test against a live MT5 gateway. The race is timing-dependent; reproducing reliably requires gateway-side coordination we don't have. Unit tests with poller-tick ordering control are the equivalent.

## Risk

**Low.** Both fixes are local to `MT5Broker`. No public API change, no event-shape change, no DSL change. The defensive cross-check in fix A only fires on the race path, which previously corrupted state — any change here is an improvement. Fix B is observability only.

Worst regression: if `client.getPositions` is slow (>pollIntervalMs), the pending-poller thread blocks for the duration of the call, delaying detection of subsequent disappearances. Mitigation: today's gateway responds in ~50ms; if that degrades we have larger problems.

## References

- Production trace: 2026-05-18 19:55:00 UTC OCO submit, 20:14:35 UTC pending fill, position 2756533542 still untracked at 20:39 UTC investigation time.
- Related fix (necessary precondition): `fix(broker): stop phantom OrderFilled on pending-entry Bracket` (v0.28.4, commit `0f65be4`).
- Source files modified:
  - `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — `onPendingDisappeared`, `onPendingPositionOpened`
  - `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerTest.kt` — four new tests
- Future architectural refactor (out of scope): unify the two pollers into a single broker-state poller that snapshots `/orders` and `/positions` in the same tick and processes openings before disappearances. Eliminates the race deterministically without per-event HTTP round-trips.
