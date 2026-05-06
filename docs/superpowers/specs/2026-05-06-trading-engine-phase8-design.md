# Phase 8 — StrategyContext and PnL Attribution

**Status:** Design draft.
**Predecessor:** Phase 7h (Bybit derivatives + rate limiting).
**Successor:** Phase 9 (risk engine; needs per-strategy PnL for equity-based limits).

---

## 1. Mission

Make every order, fill, position, and PnL number traceable to the strategy that produced it. After this phase, the engine answers "did Strategy X make money?" — a question it cannot answer today because strategies are anonymous and PnL is global.

The phase also closes the load-bearing DSL gap identified in the indicator/strategy/session audit: introduce `StrategyContext` as the bundle a strategy receives at every event, including `strategyId`, session bits, and filtered views of positions and PnL. After this phase, a DSL `strategy("ema-cross") { ... }` block has a coherent context type to compile into.

---

## 2. Goals

- `StrategyContext` data class replaces `SessionContext`. Carries `strategyId, mode, clock, calendar, source, positions: StrategyPositionView, pnl: StrategyPnLView`.
- `Strategy` interface methods take `StrategyContext` instead of `SessionContext`. Both `onTick` and `onCandle` get the same type.
- `TradingPipeline` constructor accepts `List<Pair<String, Strategy>>` — name is `strategyId`. Names must be unique within a pipeline.
- `OrderRequest` sealed variants (Market, Limit, Stop, StopLimit, IfTouched, Bracket, OCO, OTO, Trailing, …) gain `strategyId: String` field.
- `Signal.toOrderRequest(id, strategyId, ts)` — converters thread strategyId.
- `BrokerEvent.OrderEvent` (the marker added in 7g) gains `strategyId: String`. Variants `OrderAccepted`, `OrderRejected`, `OrderFilled`, `OrderPartiallyFilled`, `OrderCancelled` carry it. Brokers thread it through from `OrderRequest` → submit → ack → fill via internal lookup tables (mirrors existing `symbolByClientOrderId`).
- `StrategyPositionTracker` — new component, tracks `(strategyId, symbol) → Position`. Updated by per-fill subscription. **Not** reset by `PositionReconciled` (attribution is independent of broker truth).
- `StrategyPnL` — new component, per-strategy realized + unrealized + total. Built on `StrategyPositionTracker` + `MarketPriceProvider`.
- `StrategyPositionView` and `StrategyPnLView` — read-only filtered views passed in `StrategyContext`. Strategies query their own state without seeing others'.
- `PositionTracker` and `PnLCalculator` (broker-truth global view) stay unchanged. They co-exist with the per-strategy components; the two answer different questions.
- Backwards-compatible at the broker layer: `BybitSpotBroker`, `BybitLinearBroker`, `LogBroker`, `PaperBroker`, `CompositeBroker` all read `request.strategyId` and emit it on events.

## Non-goals

- **No automatic strategy-view drift detection.** When `PositionReconciled` fires (broker truth diverges from sum-of-strategies), no automatic correction. Strategies that care subscribe to the event and decide. Per industry convention: attribution is informational; broker is reality.
- **No equity curve / drawdown time series.** Phase 9 (risk engine) needs equity over time; build there.
- **No cross-strategy hedging detection.** If Strategy A is long 1 BTC and Strategy B is short 1 BTC, broker net is flat but strategies still each carry their own position attribution. The engine doesn't detect or warn about this — it's correct behavior for attribution.
- **No anonymous strategies.** Every strategy must have a non-empty unique `strategyId`. No auto-numbering ("strategy-0", "strategy-1") — hides intent. Test fixtures pass `"test"` or descriptive names.
- **No PnL-driven order management.** `OrderManager` and `Broker` never see PnL state. Strategies that want PnL-aware logic read `ctx.pnl` themselves.
- **No `strategyId` on broker-level events** (`PositionReconciled`, `BalancesUpdated`). Those are venue-originated, not strategy-originated. Strategies infer attribution from `source` and symbol prefix.
- **No multi-account.** One strategy can route to multiple brokers via `CompositeBroker`, but lives in a single pipeline / account context. Multi-account is its own future phase.
- **No persistence of strategy state across JVM restarts.** Same as 7f/7g/7h.

---

## 3. Background — current state (Phase 7h, post-merge)

```kotlin
// strategy package
data class SessionContext(val mode, val clock, val calendar, val source)

interface Strategy {
    fun onTick(tick, ctx: SessionContext, emit: (Signal) -> Unit)
    fun onCandle(candle, ctx: SessionContext, emit: (Signal) -> Unit) {}
}

// pipeline
class TradingPipeline(
    strategies: List<Strategy>,            // anonymous
    // ...
)

// execution
sealed class OrderRequest {
    abstract val id: String                 // unique per request, no strategy attribution
    // ...
}

// events
sealed interface BrokerEvent {
    sealed interface OrderEvent : BrokerEvent {
        val clientOrderId: String
        val brokerOrderId: String?
        // No strategyId
    }
    // ... variants ...
}

// pnl
class PnLCalculator(positions, prices) : PnLProvider {
    fun realizedTotal(): BigDecimal         // global
    fun unrealizedFor(symbol): BigDecimal   // global by symbol; no strategy axis
}

// positions
class PositionTracker : PositionProvider {
    fun applyFill(event): BigDecimal        // global by symbol
    fun reset(symbol, qty, avgPx)           // broker-truth resync
    fun positionFor(symbol): Position?
}
```

Limitations forcing this phase:

- **Strategies are anonymous.** No `strategyId` anywhere. Multiple strategies in the same pipeline can't be distinguished in events, logs, or PnL.
- **PnL is global.** `realizedTotal()` is the sum across all strategies; can't ask "did Strategy X make money?"
- **Position is global.** `PositionTracker` tracks one position per symbol. If Strategies A and B both trade BTCUSDT, their fills are commingled.
- **Strategies see broker truth, not their own attribution.** A strategy querying `positionFor("BTCUSDT")` sees the net of all strategies, not its own.
- **DSL is blocked on this gap.** The audit identified `StrategyContext` as the load-bearing missing piece; without it DSL has to re-emit per-strategy wiring.

---

## 4. Architecture overview

```
┌────────────────────────────────────────────────────────────────────────┐
│  TradingPipeline                                                       │
│    constructor(strategies: List<Pair<String, Strategy>>)               │
│    for each (id, strategy):                                            │
│      val ctx = StrategyContext(id, mode, clock, calendar, source,      │
│                                positions = StrategyPositionView(...),  │
│                                pnl = StrategyPnLView(...))             │
│      bus.subscribe<TickEvent> { strategy.onTick(e.tick, ctx, emit) }   │
│                                                                         │
│    Signal → OrderRequest carries strategyId                            │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
            │                                          ▲
            │ submit                                   │ events
            ▼                                          │
┌────────────────────────────────────────────────────────────────────────┐
│  Broker layer (unchanged shape)                                        │
│    OrderRequest.strategyId → SubmitAck → BrokerEvent.*Event.strategyId │
│    Broker maintains symbolByClientOrderId + strategyByClientOrderId    │
│      to thread strategyId on WS-driven events                          │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
            │
            │ BrokerEvent.OrderFilled
            ▼
┌────────────────────────────────────────────────────────────────────────┐
│  Two parallel trackers (different semantics)                           │
│                                                                         │
│  PositionTracker (existing, broker-truth)                              │
│    Map<symbol, Position>                                               │
│    applyFill(event) — global net                                       │
│    reset(symbol, qty, avgPx) — driven by PositionReconciled            │
│                                                                         │
│  StrategyPositionTracker (NEW, attribution)                            │
│    Map<(strategyId, symbol), Position>                                 │
│    applyFill(event) — uses event.strategyId                            │
│    NO reset on reconcile (attribution stays)                           │
│                                                                         │
│  PnLCalculator (existing)             StrategyPnL (NEW)                │
│    global realized + unrealized       per-strategyId realized + unr.   │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
```

The right column is what's new in Phase 8. Both column types are needed — broker-truth and attribution answer different questions.

---

## 5. `StrategyContext`

### Definition

```kotlin
package com.qkt.strategy

data class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val positions: StrategyPositionView,
    val pnl: StrategyPnLView,
)
```

### `StrategyPositionView` interface

```kotlin
package com.qkt.positions

interface StrategyPositionView {
    fun positionFor(symbol: String): Position?
    fun allPositions(): Map<String, Position>
}
```

Filtered to one strategy. Backed by `StrategyPositionTracker` with the strategyId bound at construction:

```kotlin
internal class StrategyPositionViewImpl(
    private val tracker: StrategyPositionTracker,
    private val strategyId: String,
) : StrategyPositionView {
    override fun positionFor(symbol: String): Position? = tracker.positionFor(strategyId, symbol)
    override fun allPositions(): Map<String, Position> = tracker.positionsFor(strategyId)
}
```

### `StrategyPnLView` interface

```kotlin
package com.qkt.pnl

interface StrategyPnLView {
    fun realized(): BigDecimal
    fun unrealizedFor(symbol: String): BigDecimal
    fun unrealizedTotal(): BigDecimal
    fun total(): BigDecimal
}
```

Same filtering pattern — internal impl binds strategyId.

### Why bundle into one type

DSL composability. A DSL strategy doesn't want to thread `(positions, pnl, mode, clock, ...)` separately — it's one thing: "the context I'm running in." The audit identified this directly.

Strategies that don't need positions/pnl simply ignore those fields. Same as ignoring `ctx` parameter today.

---

## 6. `Strategy` interface change

```kotlin
package com.qkt.strategy

interface Strategy {
    fun onTick(
        tick: Tick,
        ctx: StrategyContext,                  // was SessionContext
        emit: (Signal) -> Unit,
    )

    fun onCandle(
        candle: Candle,
        ctx: StrategyContext,                  // was SessionContext
        emit: (Signal) -> Unit,
    ) {}
}
```

`SessionContext` is deleted. All bits live on `StrategyContext`. Strategies that don't need `positions` or `pnl` simply don't read them.

### Why not keep `SessionContext` as a parent

Tempting: `data class StrategyContext(val sessionContext: SessionContext, ...)`. Rejected — adds an indirection (`ctx.sessionContext.clock` vs `ctx.clock`). DSL ergonomics matter; flat access wins. The 4 SessionContext fields are inlined.

---

## 7. `TradingPipeline` — named strategy registration

### New constructor signature

```kotlin
class TradingPipeline(
    private val bus: EventBus,
    private val clock: Clock,
    private val calendar: TradingCalendar,
    private val source: MarketSource,
    private val mode: Mode,
    private val strategies: List<Pair<String, Strategy>>,    // (strategyId, strategy)
    private val ids: IdGenerator,
    private val positions: PositionTracker,                  // broker-truth (existing)
    private val strategyPositions: StrategyPositionTracker,  // attribution (new)
    private val pnl: PnLCalculator,                          // broker-truth (existing)
    private val strategyPnL: StrategyPnL,                    // attribution (new)
    // ... other existing args ...
)
```

### Validation at construction

```kotlin
init {
    require(strategies.map { it.first }.toSet().size == strategies.size) {
        "Strategy IDs must be unique: ${strategies.map { it.first }}"
    }
    require(strategies.all { it.first.isNotBlank() }) {
        "Strategy ID must be non-blank"
    }
}
```

### Subscription wiring

```kotlin
strategies.forEach { (strategyId, strategy) ->
    val ctx = StrategyContext(
        strategyId = strategyId,
        mode = mode,
        clock = clock,
        calendar = calendar,
        source = source,
        positions = StrategyPositionViewImpl(strategyPositions, strategyId),
        pnl = StrategyPnLViewImpl(strategyPnL, strategyPositions, prices, strategyId),
    )
    bus.subscribe<TickEvent> { e ->
        strategy.onTick(e.tick, ctx) { sig ->
            val request = sig.toOrderRequest(ids.next(), strategyId, clock.now())
            bus.publish(SignalEvent(sig, strategyId))    // optional — discussed below
        }
    }
    bus.subscribe<CandleEvent> { e ->
        strategy.onCandle(e.candle, ctx) { sig -> /* same */ }
    }
}
```

### `SignalEvent` carries strategyId too?

`SignalEvent` is the event published when a strategy emits a signal, before risk approves. Today it carries `signal: Signal` only. Phase 8 needs strategyId in the SignalEvent too, so risk and observability can see which strategy produced what.

Add `val strategyId: String` to `SignalEvent`. Mechanical change; `Signal` itself stays clean (it's a strategy emission, attribution is added by the pipeline).

---

## 8. `OrderRequest.strategyId`

Every sealed variant of `OrderRequest` gains a `strategyId: String` field. The fields propagate through the order lifecycle:

```kotlin
sealed class OrderRequest {
    abstract val id: String
    abstract val strategyId: String        // NEW
    abstract val symbol: String
    abstract val side: Side
    abstract val quantity: BigDecimal
    abstract val timeInForce: TimeInForce
    abstract val timestamp: Long

    data class Market(
        override val id: String,
        override val strategyId: String,   // NEW
        override val symbol: String,
        // ...
    ) : OrderRequest()

    // Same pattern for Limit, Stop, StopLimit, IfTouched, Bracket, OCO, OTO, Trailing, etc.
}
```

### `Signal.toOrderRequest`

```kotlin
fun Signal.toOrderRequest(
    id: String,
    strategyId: String,        // NEW
    timestamp: Long,
): OrderRequest = when (this) {
    is Signal.Buy -> OrderRequest.Market(id, strategyId, symbol, Side.BUY, size, TimeInForce.IOC, timestamp)
    is Signal.Sell -> OrderRequest.Market(id, strategyId, symbol, Side.SELL, size, TimeInForce.IOC, timestamp)
    is Signal.Submit -> request.withStrategyId(strategyId)    // copies request adding the field
}
```

For `Signal.Submit(request)` — strategy passed an OrderRequest directly. The strategy didn't put its strategyId on it (doesn't know it). Pipeline adds at the converter step via `request.withStrategyId(...)` — a copy with strategyId set. Implemented per variant since data classes don't have generic copy-modify.

### `OrderRequest.withStrategyId(id)` helper

```kotlin
fun OrderRequest.withStrategyId(strategyId: String): OrderRequest = when (this) {
    is OrderRequest.Market -> copy(strategyId = strategyId)
    is OrderRequest.Limit -> copy(strategyId = strategyId)
    // ... per variant
}
```

### Bracket / OCO / OTO inheritance

Composite orders (Bracket, OCO, OTO) contain child OrderRequests. The child requests inherit the parent's strategyId at construction time, OR `OrderManager` propagates strategyId during decomposition. Either works; OrderManager-driven is cleaner because strategies don't repeat the field on every child.

---

## 9. `BrokerEvent.OrderEvent` attribution

```kotlin
sealed interface BrokerEvent : Event {
    sealed interface OrderEvent : BrokerEvent {
        val clientOrderId: String
        val brokerOrderId: String?
        val strategyId: String          // NEW
    }

    data class OrderAccepted(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val strategyId: String,    // NEW
        // ...
    ) : OrderEvent

    // OrderRejected, OrderFilled, OrderPartiallyFilled, OrderCancelled — same pattern
}
```

### Brokers thread strategyId through

Every broker (`LogBroker`, `PaperBroker`, `CompositeBroker`, `BybitSpotBroker`, `BybitLinearBroker`):

1. **On submit**: read `request.strategyId`, store in internal `strategyByClientOrderId: Map<String, String>` (mirrors existing `symbolByClientOrderId`).
2. **On submit response**: emit `OrderAccepted(strategyId = request.strategyId)`.
3. **On WS frame** (Bybit): look up `strategyByClientOrderId[clientOrderId]` and include in emitted event. If lookup fails (rare — implies a fill for an order we don't know about), use empty string `""` and log warning.
4. **On terminal events** (filled/cancelled/rejected): prune `strategyByClientOrderId` alongside `symbolByClientOrderId`.
5. **State recovery emissions**: recovery has access to `getKnownOrders()` which already maps clientOrderId to a `ManagedOrderView`. Extend `ManagedOrderView` to include `strategyId`. Reconcile-emitted `OrderCancelled` events carry the strategyId of the original order.

### `BrokerEvent.PositionReconciled`, `BalancesUpdated`

These do NOT gain `strategyId`. They are venue-originated (not strategy-originated). Subscribers infer attribution from `source` (broker prefix) and symbol.

---

## 10. `StrategyPositionTracker`

### Definition

```kotlin
package com.qkt.positions

class StrategyPositionTracker {
    private val byStrategy: MutableMap<String, MutableMap<String, Position>> = ConcurrentHashMap()

    fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal {
        // mirrors PositionTracker logic, but keys on (strategyId, symbol)
        val strategyMap = byStrategy.getOrPut(event.strategyId) { ConcurrentHashMap() }
        // ... same delta/realized math as PositionTracker.applyFill
        return realized
    }

    fun positionFor(strategyId: String, symbol: String): Position? =
        byStrategy[strategyId]?.get(symbol)

    fun positionsFor(strategyId: String): Map<String, Position> =
        byStrategy[strategyId]?.toMap() ?: emptyMap()

    fun allByStrategy(): Map<String, Map<String, Position>> =
        byStrategy.mapValues { it.value.toMap() }
}
```

### Why `ConcurrentHashMap`

EventBus dispatch is single-threaded today, but periodic reconcile + WS callbacks both touch related state from different threads. CHM keeps reads safe even if writes happen concurrently in future. Cheap and right.

### Why not reset on `PositionReconciled`

`PositionReconciled` carries a `(symbol, newQty, newAvgPx)`. It does NOT carry a `strategyId` — the venue doesn't know strategies. To "reset" `StrategyPositionTracker` we'd have to:

- Pick a strategy to attribute the new position to (arbitrary).
- Or distribute proportionally (lossy).
- Or zero out all strategies for the symbol (data loss).

None are correct. The right move: leave attribution alone. Document that drift between sum-of-strategies and broker-truth is a real signal for a strategy author to investigate.

### Drift detection helper (optional, low cost)

```kotlin
class StrategyPositionTracker {
    // ...
    fun driftFor(symbol: String, brokerView: PositionProvider): BigDecimal {
        val strategySum = byStrategy.values.sumOf {
            it[symbol]?.quantity ?: BigDecimal.ZERO
        }
        val broker = brokerView.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
        return strategySum.subtract(broker).setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

Strategies / observability components can call this on `PositionReconciled` to detect divergence. Not auto-corrective; informational.

---

## 11. `StrategyPnL`

```kotlin
package com.qkt.pnl

class StrategyPnL(
    private val strategyPositions: StrategyPositionTracker,
    private val prices: MarketPriceProvider,
) {
    private val realizedByStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun recordRealized(strategyId: String, realized: BigDecimal) {
        val current = realizedByStrategy[strategyId] ?: Money.ZERO
        realizedByStrategy[strategyId] = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun realizedFor(strategyId: String): BigDecimal =
        realizedByStrategy[strategyId] ?: Money.ZERO

    fun unrealizedFor(strategyId: String, symbol: String): BigDecimal {
        val pos = strategyPositions.positionFor(strategyId, symbol) ?: return Money.ZERO
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        return price
            .subtract(pos.avgEntryPrice)
            .multiply(pos.quantity)
            .setScale(Money.SCALE, Money.ROUNDING)
    }

    fun unrealizedTotalFor(strategyId: String): BigDecimal =
        strategyPositions.positionsFor(strategyId).keys
            .map { unrealizedFor(strategyId, it) }
            .fold(Money.ZERO) { acc, v -> acc.add(v) }
            .setScale(Money.SCALE, Money.ROUNDING)

    fun totalFor(strategyId: String): BigDecimal =
        realizedFor(strategyId).add(unrealizedTotalFor(strategyId))
            .setScale(Money.SCALE, Money.ROUNDING)
}
```

### `StrategyPnLViewImpl`

```kotlin
internal class StrategyPnLViewImpl(
    private val pnl: StrategyPnL,
    private val strategyId: String,
) : StrategyPnLView {
    override fun realized(): BigDecimal = pnl.realizedFor(strategyId)
    override fun unrealizedFor(symbol: String): BigDecimal = pnl.unrealizedFor(strategyId, symbol)
    override fun unrealizedTotal(): BigDecimal = pnl.unrealizedTotalFor(strategyId)
    override fun total(): BigDecimal = pnl.totalFor(strategyId)
}
```

---

## 12. Wiring in `TradingPipeline`

```kotlin
init {
    // ... existing wiring ...

    bus.subscribe<BrokerEvent.OrderFilled> { e ->
        // Global broker-truth path (existing 7h)
        val realized = positions.applyFill(e)
        pnl.recordRealized(realized)

        // Per-strategy attribution path (new in 8)
        val stratRealized = strategyPositions.applyFill(e)
        strategyPnL.recordRealized(e.strategyId, stratRealized)

        // ... TradeEvent publishing, onFilled callback ...
    }

    // PositionReconciled subscription — global only (per 7h, attribution stays as-is)
    bus.subscribe<BrokerEvent.PositionReconciled> { e ->
        positions.reset(e.symbol, e.newQty, e.newAvgPx)
    }
}
```

Both paths run on every fill. The per-strategy path uses `event.strategyId`.

---

## 13. Testing approach

### Unit tests

| Class | Scope |
|---|---|
| `StrategyContextTest` | Construction validation, view filtering |
| `StrategyPositionTrackerTest` | Per-strategy fills don't leak between strategyIds; same-symbol independent positions |
| `StrategyPnLTest` | realizedFor/unrealizedFor/total per strategy; cross-strategy fills don't bleed |
| `OrderRequestStrategyIdTest` | All variants carry strategyId; `withStrategyId` copy preserves other fields |
| `BrokerEventOrderEventStrategyIdTest` | All event variants carry strategyId; bus round-trip |
| `TradingPipelineRegistrationTest` | Duplicate names throw; blank names throw; per-strategy contexts have correct ID |
| `BybitSpotBrokerStrategyAttributionTest` | strategyByClientOrderId threading through submit + WS frames |
| `BybitLinearBrokerStrategyAttributionTest` | Same, for linear |

### Existing tests

Many sites: `BybitSpotBrokerTest`, `BybitLinearBrokerTest`, `LogBrokerTest`, `PaperBrokerTest`, `EndToEndTest`, `BacktestTest`, `LiveSessionTest`, etc. construct `OrderRequest` directly. All gain `strategyId = "test"` on each call.

Strategy test classes (`EveryNthTickBuyStrategyTest`, `EmaCrossoverStrategyTest`, `RollingHighBreakoutStrategyTest`, `BreakoutOfYesterdayHighStrategyTest`) already pass `testSessionContext()`. Replace with `testStrategyContext()` (helper updated to construct StrategyContext).

`TestSessionContext` helper renamed to `TestStrategyContext`. Returns `StrategyContext` with sensible test defaults (strategyId = "test", positions = no-op view, pnl = no-op view).

### `e2e-live`

`BybitSpotLiveSmokeTest` and `BybitLinearLiveSmokeTest` get a real `strategyId` ("live-smoke") and assert that emitted `OrderAccepted` carries it.

---

## 14. Race conditions and edge cases

### Race-1: fill arrives before pipeline subscribes

Pipeline init order:
1. Construct trackers + pnl
2. Subscribe to OrderFilled
3. Construct broker(s)
4. Construct strategies, subscribe each to TickEvent / CandleEvent

If a broker fires `OrderFilled` between steps 1 and 2 (impossible — broker isn't constructed yet), the event is lost. After step 2, all subsequent fills hit both global and strategy paths.

Reconcile fires at broker init (post step 3). At that point, the OrderFilled subscription is live, so reconcile-emitted fills go through both trackers. Correct.

### Race-2: WS-driven fill for unknown order

Broker receives WS execution frame for a clientOrderId not in `strategyByClientOrderId` (e.g., an order placed in a previous JVM run, recovered by 7h's reconcile). Lookup returns null.

Resolution: emit `OrderFilled(strategyId = "")` (empty string) and log WARN. The fill still records to global PositionTracker (broker truth). The empty strategyId means StrategyPositionTracker creates an entry for `("", symbol)` — orphan attribution. Strategies querying `ctx.positions.positionFor(symbol)` don't see it (their strategyId is non-empty by validation).

Alternative: drop the strategy-side update entirely if strategyId is blank. Cleaner. **Use this:**

```kotlin
fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal {
    if (event.strategyId.isBlank()) return Money.ZERO       // skip orphan attribution
    // ... rest of logic
}
```

### Race-3: same strategyId in two pipelines

Pipelines are independent; each validates uniqueness within itself. Two pipelines with overlapping strategyIds is a deployment bug, not an engine concern.

### Race-4: `PositionReconciled` resets broker view; per-strategy view diverges

Strategy A bought 1 BTC. Engine sees A's position = 1, broker = 1. Bybit liquidates externally. Reconcile fires, broker = 0. Engine: PositionTracker = 0, StrategyPositionTracker[A, BTC] = 1.

Strategy A queries `ctx.positions.positionFor("BTCUSDT")` → still sees 1.
Strategy A queries `ctx.pnl.unrealizedFor("BTCUSDT")` → uses last price × 1; reflects "ghost" PnL on a position that doesn't exist.

Per design (§13 spec), this drift is informational. Strategies subscribing to `PositionReconciled` can call `strategyPositions.driftFor("BTCUSDT", positionTracker)` to detect.

### Edge: `Signal.Submit(request)` where request.strategyId is already set

If a DSL or advanced strategy explicitly constructs `OrderRequest` with a strategyId, pipeline's `withStrategyId` could overwrite. Decision: pipeline always overwrites. Strategies can't fake their identity to the engine. Document in changelog.

### Edge: bracket / OCO child requests

`OrderRequest.Bracket(entry: OrderRequest.Limit, takeProfit, stopLoss)`. `entry.strategyId` set by strategy; OrderManager decomposes into child requests on fill. Children inherit parent strategyId via `withStrategyId`.

---

## 15. Configuration

No new env vars. No new constructor knobs beyond the named-strategy registration.

---

## 16. Multi-broker scaling

Per-strategy attribution scales naturally:
- `StrategyPositionTracker` keys on `(strategyId, symbol)`. Symbol prefix carries broker attribution. Per-broker per-strategy view is a `groupBy` away (e.g., `tracker.positionsFor(strategyId).filter { it.key.startsWith("BYBIT_LINEAR:") }`).
- `StrategyPnL.realizedFor(strategyId)` is broker-agnostic; combine with symbol-prefix filter for per-broker-per-strategy.
- Future "Phase 12 reporting" can build dashboards trivially: pivot on `(strategyId, brokerPrefix)` over the events log.

---

## 17. Out of scope (deferred)

| Feature | Phase | Rationale |
|---|---|---|
| Equity curve / drawdown time series | 9 | Risk engine consumes equity over time. |
| Cross-strategy hedging detection | future | Engine doesn't detect; strategies that care subscribe. |
| Auto-correction on `PositionReconciled` drift | (never as designed) | Lossy / arbitrary; logical attribution must stay independent. |
| PnL persistence across JVM restarts | future | State store needed. |
| Multi-account | future | Single account per pipeline. |
| `StrategyContext.balances` | 9+ | Risk engine needs balance gating; add then. |
| `StrategyContext.onFill` push-style helper | DSL phase (11) | Pull-driven onTick is fine until DSL needs declarative fill handlers. |
| Strategy lifecycle hooks (init, shutdown) | DSL phase (11) | Not needed for current strategies. |
| Per-strategy risk limits | 9 | That's risk's job. |

---

## 18. Migration

### From 7h → 8

| 7h | 8 | Notes |
|---|---|---|
| `data class SessionContext(...)` | deleted | Use `StrategyContext` everywhere. |
| `Strategy.onTick(tick, ctx: SessionContext, emit)` | `onTick(tick, ctx: StrategyContext, emit)` | Same shape; richer type. |
| `Strategy.onCandle(...)` | gains `ctx: StrategyContext` | Already had it, just renamed type. |
| `TradingPipeline(strategies: List<Strategy>)` | `TradingPipeline(strategies: List<Pair<String, Strategy>>)` | Caller registers each with a name. |
| `OrderRequest.Market(id, symbol, side, ...)` | `OrderRequest.Market(id, strategyId, symbol, side, ...)` | `strategyId` required. |
| `Signal.toOrderRequest(id, ts)` | `Signal.toOrderRequest(id, strategyId, ts)` | Pipeline passes strategyId. |
| `BrokerEvent.OrderEvent { clientOrderId, brokerOrderId }` | gains `strategyId` | Variants gain field. |
| `PositionTracker` only | `PositionTracker` + `StrategyPositionTracker` | Two trackers, two purposes. |
| `PnLCalculator` only | `PnLCalculator` + `StrategyPnL` | Two PnL views. |
| `testSessionContext()` | `testStrategyContext()` | Same defaults plus strategyId = "test". |

### Application setup change

```kotlin
// 7h
val positions = PositionTracker()
val pnl = PnLCalculator(positions, prices)
val strategies = listOf(
    EveryNthTickBuyStrategy("XAUUSD"),
    EmaCrossoverStrategy("XAUUSD"),
)
val pipeline = TradingPipeline(bus, clock, calendar, source, mode, strategies, ids, positions, pnl, ...)

// 8
val positions = PositionTracker()
val pnl = PnLCalculator(positions, prices)
val strategyPositions = StrategyPositionTracker()                         // NEW
val strategyPnL = StrategyPnL(strategyPositions, prices)                  // NEW

val strategies = listOf(
    "every-nth-buy"  to EveryNthTickBuyStrategy("XAUUSD"),                // named
    "ema-cross-xau"  to EmaCrossoverStrategy("XAUUSD"),                   // named
)

val pipeline = TradingPipeline(
    bus, clock, calendar, source, mode,
    strategies,
    ids,
    positions, strategyPositions,                                          // both trackers
    pnl, strategyPnL,                                                      // both pnls
    ...
)
```

The named registration is the one breaking change for application authors. Mechanical: add a string per strategy.

---

## 19. Summary

Phase 8 makes strategies first-class citizens: every order, fill, position, and PnL number is traceable to a named strategy. The `StrategyContext` bundle replaces `SessionContext` and gives strategies a coherent type to receive (the DSL prerequisite identified by the audit). Per-strategy `StrategyPositionTracker` and `StrategyPnL` parallel the existing global broker-truth components, answering different questions: "what does the venue think?" vs "what did this strategy do?" Both are kept; they diverge under broker-side reconcile, which is treated as informational drift.

Surface area is moderate: one new context type, one new tracker, one new PnL component, two new view interfaces, one new field on every order/event variant, and a named-registration constructor change. Risk is bounded — the changes are mechanical and the existing global-view components stay in place. After Phase 8, the engine answers strategy-attribution questions, and DSL design has a load-bearing context type to compile into.

Phase 9 (risk) builds on this directly: equity-based limits need per-strategy PnL; the StrategyContext can be extended with `risk: RiskView` when that lands.
