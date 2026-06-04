# Live market-data feed auto-recovery (#65, sub-project 2) — Design

**Goal:** A live strategy survives a transient market-data feed disconnect — the feed reconnects and
tick ingestion resumes — instead of the session dying. If the feed stays down past a bounded budget,
the session terminates (fail loud) rather than hanging or running blind.

**Why:** The chaos work (#65 sub-project 1) surfaced a gap: the **broker/private-WS** path recovers
(`ReconnectSupervisor` + reconcile), but the **market-data feed** path does not. When a live feed's
source disconnects, `LiveTickFeed` sets `sourceDisconnected = true` and never clears it, `next()`
returns `null`, and `LiveSession`'s engine loop (`while (running) { feed.next() ?: break }`) breaks and
ends the session. The Bybit socket reconnects transparently underneath, but nothing tells the feed, so
it stays dead. This closes that gap.

## Key finding — the data path already self-heals; only the signal is missing

- `BybitPublicWs` reconnects its websocket with exponential backoff on close/failure.
- `BybitPublicWsClient` is **reconnect-safe**: its `onConnected()` re-subscribes after a disconnect
  (tracked via `hasDisconnected`), so ticks resume flowing to `onTick` automatically.
- The gap: `LiveTickSource.start(onTick, onError, onDisconnect)` has **no `onReconnect`** callback, so the
  resumed connection never tells `LiveTickFeed` to clear `sourceDisconnected` — and `next()` has already
  returned `null` and killed the session before the resumed ticks arrive.

So this is a **bridge + don't-terminate-on-blip** change, not a reconnection reimplementation.

## Architecture

1. **`LiveTickSource.start(...)`** — add `onReconnect: () -> Unit = {}` (mirrors `onDisconnect`; default
   no-op, so any source that does not yet signal reconnects — e.g. MT5 — is unaffected and keeps today's
   terminate-on-disconnect behavior).
2. **`BybitPublicWsClient`** — its `onConnected()` already re-subscribes after a disconnect; have it also
   invoke an `onReconnect` callback (threaded through `subscribe(...)`).
3. **`BybitMarketSource`'s `LiveTickSource`** — forward that `onReconnect` to `start`'s callback.
4. **`LiveTickFeed`:**
   - On `onReconnect`: `sourceDisconnected.set(false)` and reset `disconnectedSinceMs` (resume).
   - On `onDisconnect`: set `sourceDisconnected = true` and record `disconnectedSinceMs = clock.now()`.
   - Rewrite `next()`: while running and not closed, poll the queue; a tick returns immediately. When the
     queue is empty **and** `sourceDisconnected`, keep waiting (do **not** return `null`) **unless** the
     disconnect has exceeded `reconnectBudgetMs` — then log an error and return `null` (terminate). Returns
     `null` only on `close()` or budget exhaustion.
   - Inject a `Clock` (default `SystemClock`) so the budget is deterministically testable.

**Budget:** `reconnectBudgetMs` constructor param, default **120_000** (2 min), configurable. Under budget
→ wait and resume; over → fail loud (the session terminates; the daemon/operator sees the strategy
stopped, rather than it silently running on no data).

**Gap on resume (v1):** resume and log a `WARN` noting the disconnect span; no re-warmup or gap-fill — the
broker reconcile owns position truth, and candle/indicator state self-heals over subsequent ticks. (YAGNI;
a future enhancement could re-warm indicators after a long gap.)

**Interaction with the transparent WS reconnect:** `LiveTickFeed` does not drive reconnection — the
source's existing backoff does. The feed only *waits, bridges the resume signal, and enforces the budget
ceiling*. No double-reconnect logic.

## Components

- `marketdata/live/LiveTickSource.kt` (modify) — add the `onReconnect` callback to `start`.
- `marketdata/live/LiveTickFeed.kt` (modify) — `onReconnect` handling, `disconnectedSinceMs`, the budget,
  the `next()` rewrite, the injected `Clock`.
- `marketdata/live/bybit/BybitPublicWsClient.kt` (modify) — fire `onReconnect` from `onConnected()` after a
  disconnect.
- `marketdata/live/bybit/BybitMarketSource.kt` (modify) — wire the source's `onReconnect` through.
- Tests:
  - `marketdata/live/FakeLiveTickSource.kt` (new test util) — fire `onTick`/`onError`/`onDisconnect`/
    `onReconnect` on command.
  - `marketdata/live/LiveTickFeedRecoveryTest.kt` (new) — survives a blip and resumes; terminates after the
    budget; a normal close still returns `null`.
  - `marketdata/live/bybit/BybitPublicWsClientReconnectTest` (new/extend) — `onConnected()` after a
    disconnect fires `onReconnect` and re-subscribes.

## Testing — deterministic, no real sockets

- `FakeLiveTickSource` drives the callbacks; a `FixedClock` advances the budget; a tiny `pollIntervalMs`
  keeps `next()` responsive. No `Thread.sleep` in assertions beyond the bounded queue poll.
- Scenarios: (a) disconnect then reconnect within budget → a tick offered after reconnect is returned by
  `next()` (survived); (b) disconnect, advance clock past budget, no reconnect → `next()` returns `null`
  (terminated); (c) `close()` → `next()` returns `null` regardless.

## Error handling

- A source `onError` keeps today's behavior (log + continue draining the queue) — errors are not the same
  as a disconnect.
- The budget-exhaustion path logs at `ERROR` with the disconnect duration before returning `null`.

## Out of scope (v1)

- MT5 feed recovery (its source keeps the no-op `onReconnect` default → unchanged; a follow-up wires its
  reconnect signal once MT5 live-feed reconnection exists).
- Gap-fill / indicator re-warmup after a long outage.
- The broker/private-WS path (already recovers — covered by #65 chaos).
- Stress and soak (the remaining #65 sub-projects).
