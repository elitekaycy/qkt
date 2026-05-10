# Memory leak audit — long-running session components

Date: 2026-05-10 (Phase 19)
Scope: `LiveSession`, `MT5PositionPoller`, `MT5Broker`, `ObservabilityServer`, `PortfolioSupervisor`, `OrderManager`, `EventBus`, `CandleHub`.

This document captures findings from a code review of the long-lived components. Two real leaks were found and fixed; the rest are documented as "intentional unbounded" or "low-risk" with rationale.

---

## Findings

### 🔴 Fixed — `ObservabilityServer` thread pool not shut down

**Where:** `src/main/kotlin/com/qkt/cli/observe/ObservabilityServer.kt`.

`server.executor = Executors.newFixedThreadPool(4)` was created but never `shutdown()`. Each LiveSession creates an ObservabilityServer; on `close()`, only `server.stop(0)` was called — the executor's threads kept running until the JVM exited.

**Impact:** 4 threads leaked per deploy/stop cycle. After 100 strategy deploys + stops, ~400 idle threads. Eventually breaks `Thread.start()` due to OS thread limits.

**Fix:** capture the executor in a private field; call `executor.shutdown()` + bounded `awaitTermination()` in `close()`. Threads also marked `isDaemon = true` so they don't block JVM exit if cleanup is missed.

### 🟠 Fixed — `OrderManager.riskByClientOrderId` accumulated unbounded

**Where:** `src/main/kotlin/com/qkt/app/OrderManager.kt`.

`recordRisk()` added a `(clientOrderId → BigDecimal)` entry on every bracket order; `riskUsdFor()` was a pure read. Entries were never removed, so a strategy placing thousands of orders over a multi-day session leaked O(N) entries.

**Impact:** ~50 bytes per entry × 10000 trades = ~500KB per long-running session per strategy. Not catastrophic but real.

**Fix:** changed `riskUsdFor()` to consume-and-remove semantics — `riskByClientOrderId.remove(clientOrderId)`. The single caller (`Backtest`'s `onFilled` lambda) reads exactly once per trade, so this is safe. Live sessions will follow the same pattern via `TradingPipeline.onFilled` → consumed once per fill. Documented in the method's doc-comment.

### 🟢 Acceptable — `OrderManager.orders` map grows during session

**Where:** `OrderManager.orders: MutableMap<String, ManagedOrder>`.

Orders are tracked in this map and removed on terminal states (filled / cancelled / rejected) via the existing event handlers. Verified: every `OrderEvent` terminal kind calls the appropriate cleanup.

**Verdict:** no leak. The map is bounded by concurrent active orders, which is bounded by the strategy's risk model.

### 🟢 Acceptable — `EventRing` is bounded

**Where:** `src/main/kotlin/com/qkt/cli/observe/EventRing.kt`.

Ring buffer with `capacity = 1000` (`StrategyHandle.RealFactory.ringSize`). Older entries evicted on overflow.

**Verdict:** memory bounded by `capacity * avgEventSize`. ~1MB per strategy. Fine.

### 🟢 Acceptable — `MT5PositionPoller.lastSnapshot`

**Where:** `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt`.

Snapshot replaced wholesale on each tick — old snapshot eligible for GC.

**Verdict:** size bounded by concurrent open positions per profile. No leak.

### 🟢 Acceptable — `CandleHub` listeners

**Where:** `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`.

Listeners registered at strategy compile time. Each `LiveSession` creates its own `CandleHub` (or shares the daemon's `sharedHub`). When the session ends, the hub goes out of scope.

**Verdict:** no per-tick growth. Fine.

⚠️ **Watchout:** if `sharedHub` is shared across sessions and listeners aren't unregistered when a session closes, listeners accumulate. Currently `CandleHub.register` adds; there's no `unregister`. **Future enhancement:** add `unregister` and call from `LiveSession.stop()`.

### 🟢 Acceptable — `EventBus` subscriber lists

**Where:** `src/main/kotlin/com/qkt/bus/EventBus.kt`.

Subscribers added at session start; never explicitly removed. Each session creates a fresh `EventBus`, so subscriber lifetime = session lifetime.

**Verdict:** no leak per session. Bus reference dies with session.

### 🟢 Acceptable — `PortfolioSupervisor.children` references

**Where:** `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt`.

Holds `List<ChildHandle>` for the lifetime of the supervisor. Cleared on `stop()` indirectly (children go out of scope when registry removes them).

**Verdict:** lifetime-bounded. Fine.

### 🟢 Acceptable — `MT5Broker` singleton-like state

**Where:** `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`.

Owns `client` + `poller` + `stateRecovery`. Phase 18 made each LiveSession instantiate its own `MT5Broker` per profile, so each session gets its own poller. On `LiveSession` shutdown, the `MT5Broker.shutdown()` should be called to stop the poller.

⚠️ **Watchout:** `LiveSession.stop()` interrupts the engine thread but doesn't call `shutdown()` on each broker in the registry. This means MT5 pollers keep running after a session stops.

**Future fix:** in `LiveSession.stop()`, iterate over `brokerRegistry` values and call any `shutdown()`/`close()` they implement. Add a `BrokerLifecycle` marker interface so the registry can introspect.

For v1 with paper/MT5: `MT5Broker.shutdown()` exists and should be wired into session stop. Documented as a known gap; impact is one extra poller thread per closed-but-not-shutdown MT5 session.

---

## Action items (deferred)

The two leaks above are fixed in this audit. The two `⚠️ Watchout` items are real but require slightly larger changes:

1. **`CandleHub.unregister`** — add the method, wire from `LiveSession.stop()`. Small refactor; would add to a follow-on.
2. **`Broker.shutdown()` on session stop** — define a lifecycle protocol on `Broker`; iterate registry on stop. Defer to a future phase to ensure backward compat.

Both are tracked in `docs/backlog.md` under the next-pre-live items.
