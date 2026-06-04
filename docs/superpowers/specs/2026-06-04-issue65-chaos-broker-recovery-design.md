# Chaos tests — broker recovery (#65, sub-project 1) — Design

**Goal:** A deterministic test suite that injects faults and asserts the engine's broker-recovery
guarantees hold under failure: it reconnects, reconciles venue state, and never loses or duplicates an
order. The tests exercise the **real** recovery components, not reimplementations.

**Why:** #65 ("stress, soak, and chaos") decomposes into three independent sub-projects. This is the
first — **chaos / broker recovery** — chosen because it directly verifies the production-readiness
guarantee that #142/#33/#44 hinge on ("does it recover under failure?"), and the recovery machinery
already exists to exercise.

## Decomposition of #65 (recorded so the other two are not lost)

1. **Chaos — broker recovery** (this spec). Verify the built reconnect + reconcile + no-loss/no-dup path.
2. **Chaos — feed recovery** (future). Today a market-data-feed disconnect is *terminal*:
   `LiveTickFeed.sourceDisconnected` is set and never cleared, `feed.next()` returns `null`, and
   `LiveSession`'s loop breaks and ends the session. That is a real gap (the feed does not auto-recover);
   a future sub-project both exposes and closes it. **Out of scope here** because it is build-a-feature,
   not verify-existing.
3. **Stress** (future) — extend `BacktestThroughputStressTest` (multi-strategy, deeper indicator chains,
   tighter throughput floors). **Soak** (future) — a long-run ops harness watching RSS/heap/threads for
   monotonic growth.

## What already exists (we build on it, not reimplement)

- `Broker` (`src/main/kotlin/com/qkt/broker/Broker.kt`) — `submit(OrderRequest): SubmitAck`,
  `cancel(orderId)`, `getOpenPositions()`, `shutdown()`. Rejection is `SubmitAck(accepted=false)`;
  async outcomes are `BrokerEvent`s on the bus. The generic fault-injection seam.
- `ReconnectSupervisor` (`src/main/kotlin/com/qkt/common/net/ReconnectSupervisor.kt`) — constructor takes
  an injectable `executor: ScheduledExecutorService` and `attemptReconnect: () -> Boolean` +
  `onReconnected: () -> Unit`; exposes `isReconnecting`, `scheduleReconnect()`, `abort()`. Drives
  exponential backoff and fires `onReconnected` exactly once on success.
- `BybitSpotStateRecovery` (`.../broker/bybit/spot/BybitSpotStateRecovery.kt`) — `reconcile()` re-fetches
  open orders / executions / balances via `BybitTransport` and publishes reconciliation `BrokerEvent`s,
  deduping executions through a `seenExecIds` set. Wired in `BybitSpotBroker` on startup, on-reconnect
  (`transport.onReconnect { recovery.reconcile() }`), and periodically (`PeriodicReconciler`).
- `BybitTransport` + `FakeBybitClient` (`src/test/.../bybit/FakeBybitClient.kt`) — `FakeBybitClient`
  already implements `BybitTransport` (`getSigned`, `postSigned`, `onDisconnect`, `onReconnect`,
  `isConnected`) — the deterministic transport seam.
- `FakeBroker` (`src/test/.../broker/FakeBroker.kt`) — order tracking + `rejectOrderIds` + `emitFill`.
- `TestScheduler` pattern in `ReconnectSupervisorTest` — a hand-rolled synchronous
  `ScheduledExecutorService` that records delays and runs tasks on `runNext()`. The determinism device.

## Architecture — deterministic, no network, no `Thread.sleep`

Every test injects a synchronous `TestScheduler` into `ReconnectSupervisor` (so reconnect attempts run
on demand, not after wall-clock delays), drives a `FakeBybitClient` to simulate disconnect→reconnect and
to return canned reconcile responses, uses a `FixedClock`, and asserts on `EventBus`-published
`BrokerEvent`s. No real sockets, no sleeps, no flakiness — the qkt testing ethos (deterministic, no
mocks: anonymous fakes + capture lists). The suite runs in **default CI** (fast), unlike the
`@Tag("stress")` throughput test.

## Components

- `src/test/kotlin/com/qkt/chaos/ChaosBroker.kt` — a `Broker` decorator wrapping a delegate, driven by a
  `ChaosFaultModel`. Intercepts `submit`/`cancel`/`getOpenPositions` to inject: a hard rejection, a thrown
  exception, an empty/stale position map. Pure test util (no production code).
- `src/test/kotlin/com/qkt/chaos/ChaosFaultModel.kt` — the fault knobs (`submitFault: NONE|REJECT|THROW`,
  `stalePositions: Boolean`). Small, immutable.
- `src/test/kotlin/com/qkt/chaos/SyncScheduler.kt` — extract the `ReconnectSupervisorTest` synchronous
  scheduler into a reusable test helper (it is currently a private inner class); both the existing test
  and the chaos suite use it. (Targeted improvement — removes duplication the chaos work would otherwise
  create.)
- `src/test/kotlin/com/qkt/chaos/ReconnectChaosTest.kt` — reconnect scenarios.
- `src/test/kotlin/com/qkt/chaos/ReconcileChaosTest.kt` — reconcile-on-reconnect, missed-fill replay,
  dedup, stale-position scenarios.
- `src/test/kotlin/com/qkt/chaos/SubmitFaultChaosTest.kt` — order-flow fault scenarios via `ChaosBroker`.

## Scenarios (assertions)

1. **Reconnect after transient failure.** `ReconnectSupervisor` with `SyncScheduler`; `attemptReconnect`
   returns false twice then true. Assert: `isReconnecting` is true after the first drop, the recorded
   backoff delays increase, `onReconnected` fires **exactly once**, `isReconnecting` is false at the end.
2. **Reconcile runs on reconnect.** Wire `FakeBybitClient.onReconnect { recovery.reconcile() }`; trigger
   the reconnect callback; assert `reconcile()` ran (a canned open-orders/executions response produced the
   expected `BrokerEvent`s).
3. **Missed fill replayed exactly once.** `FakeBybitClient` returns an execution (id `E1`) the engine
   never saw; reconcile publishes one `OrderFilled` for it; a second reconcile with the same `E1` (still
   in the response) publishes **no** further fill (`seenExecIds` dedup).
4. **Vanished open order → cancel.** A locally-tracked order absent from the venue's open-orders response
   → reconcile publishes `OrderCancelled` for it.
5. **Stale-position reconcile.** The venue reports a position the engine did not track → a
   `PositionReconciled` event re-syncs it.
6. **Submit fault.** `ChaosBroker` injects `REJECT`/`THROW` on `submit` → the order is rejected
   (`SubmitAck.accepted == false` and/or `BrokerEvent.OrderRejected`), no `OrderFilled` follows, and the
   `OrderManager` marks the order `REJECTED`.

## Error handling / determinism notes

- No scenario uses wall-clock timing; `SyncScheduler.runNext()` advances reconnect attempts.
- Each test constructs its own `EventBus`, `FixedClock`, and fakes — no shared mutable state.
- Assertions are on observable outcomes (published `BrokerEvent`s, `isReconnecting`, order state), never
  on "we called X" — per the no-mocked-behavior rule.

## Testing

The suite *is* the deliverable. The only new non-test code is none; the only refactor is extracting the
`SyncScheduler` helper (with `ReconnectSupervisorTest` migrated onto it to prove the extraction is
behavior-preserving). Coverage is the six scenarios above.

## Out of scope (v1)

- Feed-recovery gap (sub-project 2); real-network / live-HTTP chaos; MT5-specific reconcile; latency via
  real timeouts; stress and soak (the other #65 sub-projects). Each is its own spec → plan.
