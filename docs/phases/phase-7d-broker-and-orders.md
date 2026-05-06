# Phase 7d — Broker Abstraction & Order Management

**Status:** Shipped. Phase 7d-a merged in `a3f91b6`. Phase 7d-b merged in (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase7d-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7d-design.md)
**Plans:** 7d-a / 7d-b (linked at the bottom).

---

## Summary

Phase 7d turns qkt's order layer from a synchronous `Broker.execute(order): Trade?` into an asynchronous, event-driven model with a richly-typed `OrderRequest` hierarchy and a new engine-side `OrderManager` that owns order lifecycle and choreographs composite types. Strategies can now express any of: Market, Limit, Stop, StopLimit, IfTouched, TrailingStop, TrailingStopLimit, StandaloneOCO, OTO, Bracket, ScaleOut, TimeExit — and compose them into arbitrary trees. The same strategy code runs identically across paper, log, and (future) real brokers because OrderManager handles all trigger logic and composite choreography in-engine.

---

## What's new

### Order types (Tier 1, 2, 3)

- `com.qkt.execution.OrderRequest` — sealed interface; replaces flat `Order` + `OrderType` enum.
  - **Tier 1 (always broker):** `Market`, `Limit`.
  - **Tier 2 (broker-native if supported, engine fallback otherwise):** `Stop`, `StopLimit`, `IfTouched`, `Bracket`.
  - **Tier 3 (always engine):** `TrailingStop`, `TrailingStopLimit`, `StandaloneOCO`, `OTO`, `ScaleOut`, `TimeExit`.
- `com.qkt.execution.TimeInForce` — enum: `DAY`, `GTC`, `IOC`, `FOK`.
- `com.qkt.execution.TriggerType` — enum: `MARKET`, `LIMIT` (used by `IfTouched`).
- `com.qkt.execution.TrailMode` — enum: `ABSOLUTE`, `PERCENT`.
- `com.qkt.execution.ExpiryAction` — enum: `CANCEL`, `CLOSE_AT_MARKET`.
- `com.qkt.execution.ScaleOutLeg` — `(priceTarget, fraction)` data class.

### Broker contract

- `com.qkt.broker.Broker` — async interface: `submit(req): SubmitAck` + `cancel(id)` + optional `modify`. Drops the old `execute → Trade?`.
- `com.qkt.broker.OrderTypeCapability` — enum: `MARKET`, `LIMIT`, `STOP`, `STOP_LIMIT`, `BRACKET`, `IF_TOUCHED`, `MODIFY`. Each broker advertises which types it handles natively.
- `com.qkt.broker.SubmitAck` — `(clientOrderId, brokerOrderId?, accepted, rejectReason?)`.
- `com.qkt.broker.OrderModification` — `(newQuantity?, newLimitPrice?, newStopPrice?)` for the `modify` path.

### Broker implementations

- `com.qkt.broker.LogBroker` — logs every submit and cancel; emits `OrderAccepted` synchronously; never emits a fill. Reference impl for strategy testing without execution semantics.
- `com.qkt.broker.PaperBroker` — refactor of the old `MockBroker`. In-process simulation of Market, Limit, Stop, StopLimit, IfTouched. Fills are emitted as `BrokerEvent`s on the bus. Subscribes to `TickEvent` directly to match working orders.

### Broker events

- `com.qkt.events.BrokerEvent` — sealed interface (extends `Event`).
  - `OrderAccepted`, `OrderRejected`, `OrderPartiallyFilled`, `OrderFilled`, `OrderCancelled`.
- `com.qkt.events.Event` — converted from sealed class to sealed interface so subtypes can live in other packages.

### OrderManager

- `com.qkt.app.OrderManager` — engine-side owner of order lifecycle.
  - Dispatches `OrderRequest`s by tier and broker capability.
  - Holds engine-pending orders for Tier 2 fallback and Tier 3 always.
  - Synthesizes triggers per tick for engine-pending orders.
  - Choreographs OCO sibling cancellation, OTO parent-fill child-activation, ScaleOut leg sequencing, TimeExit deadline expiry, Bracket native or OTO+OCO fallback decomposition.
  - Public surface: `submit(req)`, `cancel(id)`, `getOrder(id)`, `activeOrders()`, `pendingOrders()`.
- `com.qkt.execution.OrderState` — enum: `CREATED`, `PENDING`, `SUBMITTED`, `WORKING`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `REJECTED`. Plus `OrderState.isTerminal` extension.
- `com.qkt.execution.ManagedOrder` — per-order tracked record with state, broker id, cumulative fill, parent/children, group id, timestamps.

### Strategy signal extension

- `com.qkt.strategy.Signal.Submit(request: OrderRequest)` — new variant. Strategies can now emit any non-Market order type by wrapping the request:
  ```kotlin
  emit(Signal.Submit(OrderRequest.Stop(...)))
  ```
  `Signal.Buy` and `Signal.Sell` continue to work as Market sugar.

### Pipeline rewire

- `TradingPipeline` constructs `OrderManager` internally; routes `OrderEvent` → `orderManager.submit(...)`.
- `BrokerEvent.OrderFilled` and `OrderPartiallyFilled` subscriptions update `PositionTracker.applyFill(...)` and `PnLCalculator.recordRealized(...)` and re-publish `TradeEvent`.
- The `(Trade, Order) → Unit` `onFilled` callback became `(Trade, BigDecimal) → Unit` (the `BigDecimal` is the realized PnL delta from this fill).

### Risk integration

- `RiskRule.evaluate(request: OrderRequest, positions)` — was `evaluate(order: Order, ...)`.
- `RiskEngine.approve(request: OrderRequest)` — same.
- `MaxPositionSize`, `MaxOpenPositions` — refactored for `OrderRequest`.

### Position + PnL

- `PositionTracker.applyFill(BrokerEvent.OrderFilled): BigDecimal` — new event-friendly entry point. The old `apply(Trade): BigDecimal` stays as a thin adapter.

---

## Migration from previous phase

| Phase 7c name | Phase 7d name | Notes |
|---|---|---|
| `com.qkt.broker.MockBroker` | `com.qkt.broker.PaperBroker` | Renamed + refactored to event-emitting. Constructor now takes `bus` first. |
| `com.qkt.execution.Order` | `com.qkt.execution.OrderRequest` (sealed) | Flat data class → sealed type with per-variant fields. |
| `com.qkt.execution.OrderType` enum | (deleted) | Folded into the sealed hierarchy. |
| `Broker.execute(order): Trade?` | `Broker.submit(request): SubmitAck` + `cancel(id)` | Async + capability-aware. |
| `Signal.toOrder(...)` extension | `Signal.toOrderRequest(...)` | Builds `OrderRequest.Market` from `Signal.Buy/Sell` sugar; passes through `Signal.Submit(req)`. |
| `RiskRule.evaluate(order, ...)` | `RiskRule.evaluate(request, ...)` | Same logic, parameter type updated. |
| `OrderEvent.order` | `OrderEvent.request` | Field rename. |
| `RiskRejectedEvent.order` | `RiskRejectedEvent.request` | Field rename. |
| `Event` sealed class | `Event` sealed interface | Allows `BrokerEvent` to live in `com.qkt.events` and be subtyped from outside the package. |
| `TradingPipeline.onFilled: (Trade, Order) -> Unit` | `(Trade, BigDecimal) -> Unit` | The `Order` is gone; the second arg is realized PnL. |

Existing strategies that emit only `Signal.Buy/Sell` need no change — those remain Market sugar. New strategies wanting Stop, Limit, Trailing, OCO, etc. emit `Signal.Submit(OrderRequest.X(...))`.

---

## Usage cookbook

### 1. Plain Market via Signal sugar

```kotlin
class BuyEveryTickStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
        if (tick.symbol == symbol) emit(Signal.Buy(symbol, Money.of("1")))
    }
}
```
Pipeline lifts `Signal.Buy` to `OrderRequest.Market` and submits via OrderManager.

### 2. Stop loss via Signal.Submit

```kotlin
emit(
    Signal.Submit(
        OrderRequest.Stop(
            id = ids.next(),
            symbol = "EURUSD",
            side = Side.SELL,
            quantity = Money.of("1"),
            stopPrice = Money.of("1.09"),
            timeInForce = TimeInForce.GTC,
            timestamp = clock.now(),
        ),
    ),
)
```
PaperBroker advertises `STOP` natively, so it sits in PaperBroker's working list. Real brokers can do the same; brokers without the capability get engine fallback.

### 3. Bracket — entry + take-profit + stop-loss

```kotlin
emit(
    Signal.Submit(
        OrderRequest.Bracket(
            id = ids.next(),
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry = OrderRequest.Limit(
                id = "${parentId}-e",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("4500"),
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            ),
            takeProfit = Money.of("4600"),
            stopLoss = Money.of("4450"),
            timeInForce = TimeInForce.GTC,
            timestamp = clock.now(),
        ),
    ),
)
```
On a broker with `BRACKET` capability, this ships as one native bracket. Otherwise, OrderManager decomposes into `OTO(entry, [OCO(Limit at 4600, Stop at 4450)])` and choreographs the children itself.

### 4. StandaloneOCO — two competing entries

```kotlin
val oco = OrderRequest.StandaloneOCO(
    id = ids.next(),
    symbol = "BTCUSDT",
    side = Side.BUY,
    quantity = Money.of("1"),
    leg1 = OrderRequest.Limit(
        id = "..._buy", symbol = "BTCUSDT", side = Side.BUY,
        quantity = Money.of("1"), limitPrice = Money.of("80000"),
        timeInForce = TimeInForce.GTC, timestamp = clock.now(),
    ),
    leg2 = OrderRequest.Limit(
        id = "..._sell", symbol = "BTCUSDT", side = Side.SELL,
        quantity = Money.of("1"), limitPrice = Money.of("90000"),
        timeInForce = TimeInForce.GTC, timestamp = clock.now(),
    ),
    timeInForce = TimeInForce.GTC, timestamp = clock.now(),
)
emit(Signal.Submit(oco))
```
Both legs go to the broker. When either fills, OrderManager cancels the sibling.

### 5. OTO with TrailingStop child — entry then trail

```kotlin
val entry = OrderRequest.Limit(
    id = "${gid}-e", symbol = "BTCUSDT", side = Side.BUY,
    quantity = Money.of("1"), limitPrice = Money.of("80000"),
    timeInForce = TimeInForce.GTC, timestamp = clock.now(),
)
val trail = OrderRequest.TrailingStop(
    id = "${gid}-t", symbol = "BTCUSDT", side = Side.SELL,
    quantity = Money.of("1"),
    trailAmount = Money.of("500"), trailMode = TrailMode.ABSOLUTE,
    timeInForce = TimeInForce.GTC, timestamp = clock.now(),
)
emit(Signal.Submit(OrderRequest.OTO(
    id = gid, symbol = "BTCUSDT", side = Side.BUY, quantity = Money.of("1"),
    parent = entry, children = listOf(trail),
    timeInForce = TimeInForce.GTC, timestamp = clock.now(),
)))
```
Buy at 80k. Once filled, OrderManager activates the trailing stop. HWM ratchets up with price; on a $500 retrace, fires Market sell.

### 6. ScaleOut — fractional exits at price targets

```kotlin
emit(Signal.Submit(OrderRequest.ScaleOut(
    id = gid, symbol = "BTCUSDT", side = Side.BUY, quantity = Money.of("3"),
    basis = OrderRequest.Market(
        id = "${gid}-e", symbol = "BTCUSDT", side = Side.BUY,
        quantity = Money.of("3"), timeInForce = TimeInForce.GTC, timestamp = clock.now(),
    ),
    legs = listOf(
        ScaleOutLeg(priceTarget = Money.of("84000"), fraction = Money.of("0.33")),
        ScaleOutLeg(priceTarget = Money.of("86000"), fraction = Money.of("0.33")),
        ScaleOutLeg(priceTarget = Money.of("88000"), fraction = Money.of("0.34")),
    ),
    timeInForce = TimeInForce.GTC, timestamp = clock.now(),
)))
```
Buy 3 BTC at market. As price rises, three `IfTouched` exits fire at 84k/86k/88k for 1 BTC each.

### 7. TimeExit — auto-cancel limit if not filled in 10 minutes

```kotlin
emit(Signal.Submit(OrderRequest.TimeExit(
    id = gid, symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
    target = OrderRequest.Limit(
        id = "${gid}-target", symbol = "EURUSD", side = Side.BUY,
        quantity = Money.of("1"), limitPrice = Money.of("1.10"),
        timeInForce = TimeInForce.GTC, timestamp = clock.now(),
    ),
    deadline = Instant.now().plus(Duration.ofMinutes(10)),
    onExpiry = ExpiryAction.CANCEL,
    timeInForce = TimeInForce.GTC, timestamp = clock.now(),
)))
```
If the Limit is still working after 10 minutes (no fill), OrderManager cancels it. Use `ExpiryAction.CLOSE_AT_MARKET` instead to flatten an already-filled position at the deadline.

### 8. Strategy testing with LogBroker

```kotlin
val broker = LogBroker(bus, clock)
val pipeline = TradingPipeline(
    clock = clock, ids = ids, sequencer = sequencer,
    priceTracker = priceTracker, positions = positions,
    pnl = pnl, bus = bus, broker = broker,
    engine = engine, strategies = listOf(strategy),
    riskEngine = riskEngine, sessionContext = ctx,
)
// Feed ticks; strategies emit; LogBroker logs every order; no fills happen.
// Tests inspect orders via orderManager.activeOrders() / pendingOrders() / getOrder(id).
```
Strategy logic is exercised end-to-end without execution semantics polluting assertions.

### 9. Backtest / live both use the same strategy code

Phase 7d preserves backtest-live parity by keeping all trigger evaluation in OrderManager, which runs the same Kotlin code in both contexts. A strategy running against `PaperBroker` in `Backtest` and against a future `AlpacaBroker` in `LiveSession` will:
- See the same `OrderRequest` types coming out of `Signal.Submit`.
- Have the same OCO/OTO/Trailing choreography fire at the same logical moments.
- Differ only in fill prices (PaperBroker: tracker mid; Alpaca: real venue execution).

### 10. Inspect order state from a strategy

```kotlin
val order = orderManager.getOrder("c1")
when (order?.state) {
    OrderState.WORKING -> // sitting at broker
    OrderState.PENDING -> // engine waiting on trigger
    OrderState.PARTIALLY_FILLED -> // partial; check cumulativeFilledQuantity
    OrderState.FILLED -> // done
    null -> // unknown
    else -> // terminal
}
```

---

## Testing patterns

### `FakeBroker` programmable capabilities

```kotlin
val broker = FakeBroker(
    bus, clock,
    capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT),
)
```
Drive engine-fallback paths by giving the broker a minimal capability set; assert on `broker.submits`, `broker.cancels`. Use `broker.emitFill(req, price)` to simulate a broker-side fill from the test thread.

### OrderManager state inspection

OrderManager tests assert on `om.getOrder(id)?.state` and `om.activeOrders()`/`pendingOrders()` rather than on broker calls. This lets a test verify the lifecycle independently of how the broker reacts.

### BrokerEvent synthesis

Bypass broker entirely and publish events directly:

```kotlin
bus.publish(BrokerEvent.OrderFilled(
    clientOrderId = "c1", brokerOrderId = "c1",
    symbol = "EURUSD", side = Side.BUY,
    price = Money.of("1.10"), quantity = Money.of("1"),
))
```
Useful for testing OCO/OTO choreography without a real fill path.

### Determinism

Backtest two-run identity tests (Phase 4 / 7b) continue to pass: same input ticks → same orders → same fills → same trades. PaperBroker emits events synchronously inline, so OrderManager's tick-driven logic stays deterministic.

---

## Known limitations

- **No real-broker integration.** `LogBroker` and `PaperBroker` are the only implementations. Real-broker adapters (Alpaca, IBKR, OANDA) land in Phase 7e+.
- **Modify is not exposed via OrderManager.** Strategies that need to amend an order should `cancel + resubmit`. The `Broker.modify` path is in the interface for future use.
- **TimeExit is tick-driven.** The deadline is checked on each `TickEvent`. In live, ticks fire frequently; in backtest, ticks advance the clock deterministically. There is no separate scheduler.
- **No state persistence across restarts.** OrderManager's pending list is in-memory. `LiveSession` rebuilds from warmup at startup; pending orders submitted before a crash are lost.
- **Multi-broker not supported.** OrderManager talks to a single `Broker`. A `CompositeBroker` analogue to `CompositeMarketSource` is deferred.
- **Risk evaluates the outer composite only.** OCO siblings, OTO children, ScaleOut legs are not individually risk-checked; assumed to unwind rather than grow exposure. ScaleOut is the edge case (legs are exits) and works correctly under that assumption.
- **`ScaleOut` leg quantity is based on the requested basis quantity, not the actual filled quantity.** Acceptable for paper/log brokers (they fill exactly the requested amount); will need refinement for real-broker partial fills (Phase 7e).
- **Position reconciliation against broker view is not implemented.** Engine-side `PositionTracker` is canonical via `OrderFilled` events.
- **`RegimeSwitch` deferred.** The spec discussed this as a Tier 3 type; deferred to Phase 8 (DSL) where conditional order trees can be expressed declaratively.
- **No commission, spread, or slippage modeling.** All fills are at the trigger price. Listed in the post-7d roadmap.

---

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase7d-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7d-design.md)
- Plan 7d-a (broker abstraction + LogBroker + PaperBroker + pipeline migration): [`../superpowers/plans/2026-05-06-trading-engine-phase7d-a.md`](../superpowers/plans/2026-05-06-trading-engine-phase7d-a.md)
- Plan 7d-b (OrderManager + composites): [`../superpowers/plans/2026-05-06-trading-engine-phase7d-b.md`](../superpowers/plans/2026-05-06-trading-engine-phase7d-b.md)
- Phase 7 baseline (live runtime + MarketSource umbrella): [`phase-7-live-runtime.md`](phase-7-live-runtime.md)
- Phase 7d-a merge SHA: `a3f91b6`.
- Phase 7d-b merge SHA: (placeholder — fill in at merge time).
