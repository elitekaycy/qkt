# Phase 7d — Broker Abstraction & Order Management

**Status:** Design draft.
**Predecessor:** Phase 7c (TradingView vendor, live runtime).
**Successor:** Phase 7e (real broker integrations: Alpaca / IBKR / OANDA-as-broker).

---

## 1. Mission

Replace the synchronous `Broker.execute(order): Trade?` abstraction with an asynchronous, event-driven broker model that:

1. Supports the full set of order types a strategy author needs to build robust risk-managed positions (Market, Limit, Stop, StopLimit, TrailingStop, IfTouched, Bracket, Standalone OCO, ScaleOut, TimeExit, RegimeSwitch).
2. Splits responsibility cleanly between the **broker** (server-resident order book, primitive submission, fill events) and the **engine** (trigger logic for the order types brokers don't natively express, composite/multi-leg choreography, position and PnL).
3. Lets new brokers slot in by implementing a small interface plus advertising a capability set; everything else (engine logic, strategies, backtest behavior) is broker-agnostic.
4. Preserves backtest-live parity: the same strategy, run against `PaperBroker` in backtest and `AlpacaBroker` (future) in production, fills at functionally equivalent prices and triggers at functionally equivalent moments.

Phase 7d ships the abstraction, the engine-side `OrderManager`, the full order type taxonomy, and **one reference broker implementation** (`LogBroker`) used to test strategies without execution semantics. Real brokers are deferred to Phase 7e+; the design must not require their presence to be complete.

---

## 2. Goals

- Broker is async: `submit(req)` is fire-and-forget; fills, rejects, and cancellations arrive as events on the existing `EventBus`.
- Order types are a sealed hierarchy on `OrderRequest` — Market, Limit, Stop, StopLimit, TrailingStop, IfTouched, Bracket, OCO, OTO, ScaleOut, TimeExit, RegimeSwitch.
- A new `OrderManager` component owns order lifecycle: pending → submitted → working → filled / cancelled / rejected.
- Brokers advertise capabilities. The OrderManager dispatches each order to the broker if the broker can handle it natively, else synthesizes the behavior in-engine.
- Strategy code is broker-agnostic: it never branches on broker identity, never inspects capabilities.
- Position tracking and PnL update from `OrderFilled` events on the bus — independent of order type or originating broker.
- Existing risk integration (`RiskEngine`, `MaxPositionSize`) continues to gate orders before submission.
- The `LogBroker` reference impl logs every primitive submission and every cancel; emits `OrderAccepted` immediately and never emits a fill (so strategies can be exercised end-to-end without execution noise).

## Non-goals

- No real broker integrations (Alpaca, IBKR, OANDA, Binance) in 7d. Their adapters land in 7e.
- No server-side order persistence guarantees. The capability flag exists; specific brokers will document their crash-safety semantics when they ship.
- No new venues, exchanges, or data sources. 7d touches the broker boundary only.
- No DSL changes. The DSL (Phase 8) will compile to the same `OrderRequest` types; no DSL surface is in scope here.
- No commission, spread, or slippage modeling. Listed in the post-7d roadmap (`tt.txt`); kept out of 7d to keep scope tight.

---

## 3. Background — current state (Phase 7c, post-merge)

```kotlin
// src/main/kotlin/com/qkt/broker/Broker.kt
interface Broker {
    fun execute(order: Order): Trade?
}

// src/main/kotlin/com/qkt/broker/MockBroker.kt
class MockBroker(clock, priceProvider) : Broker {
    override fun execute(order: Order): Trade? {
        // MARKET → fill at last price; LIMIT/STOP → fill at order.price
    }
}

// src/main/kotlin/com/qkt/execution/Order.kt
data class Order(id, symbol, side, quantity, type: OrderType, price: BigDecimal? = null, timestamp)

// src/main/kotlin/com/qkt/execution/OrderType.kt
enum class OrderType { MARKET, LIMIT, STOP }
```

Current shape:

- Synchronous: `execute → Trade?` returns a fill (or null) inline.
- Three order types as a flat enum with optional `price` field.
- `MockBroker` simulates fills against the in-process `MarketPriceProvider`.
- `TradingPipeline` calls `broker.execute(...)` directly and applies the returned `Trade` to `PositionTracker` and `PnLCalculator` synchronously.
- No order state, no cancel, no modify, no partial fills, no broker events.

Limitations forcing this redesign:

- Real brokers are network-async; `execute → Trade?` cannot model "submit now, fill later, possibly partial".
- The `OrderType` enum + optional `price` field is lossy: STOP has a trigger price but no limit price; STOP_LIMIT needs both; the schema cannot express either cleanly.
- No place to express cancel, modify, OCO siblings, OTO parents.
- No backtest-live parity contract: each future broker would simulate complex orders independently.

---

## 4. Architecture overview

### 4.1 Three-tier dispatch

Every `OrderRequest` falls into one of three handling tiers:

```
┌──────────────────────────────────────────────────────────────┐
│ Tier 1 — ALWAYS broker                                       │
│   Market, Limit                                              │
│   These live in the venue's order book. The broker is the    │
│   only entity that can place them there.                     │
├──────────────────────────────────────────────────────────────┤
│ Tier 2 — BROKER-NATIVE if supported, ENGINE FALLBACK if not  │
│   Stop, StopLimit, IfTouched, Bracket                        │
│   Most modern brokers handle these. When a broker advertises │
│   the capability, the OrderManager ships the order whole;    │
│   when it doesn't, the engine synthesizes by watching ticks  │
│   and converting to a Tier 1 order at trigger time.          │
├──────────────────────────────────────────────────────────────┤
│ Tier 3 — ALWAYS engine                                       │
│   TrailingStop, StandaloneOCO, OTO (general), ScaleOut,      │
│   TimeExit, RegimeSwitch, and any future composite the       │
│   engine introduces.                                         │
│   Either no broker natively expresses these (TrailingStop    │
│   semantics differ too much across brokers; StandaloneOCO of │
│   two pending entries isn't a standard broker primitive), or │
│   the type is engine-internal compositional logic.           │
└──────────────────────────────────────────────────────────────┘
```

The dispatch table:

```kotlin
fun dispatch(req: OrderRequest) {
    when (req) {
        is Market, is Limit -> broker.submit(req)
        is Stop, is StopLimit, is IfTouched, is Bracket ->
            if (req.requiredCapability in broker.capabilities) {
                broker.submit(req)
            } else {
                synthesize(req)
            }
        is TrailingStop, is StandaloneOCO, is OTO, is ScaleOut,
        is TimeExit, is RegimeSwitch -> synthesize(req)
    }
}
```

Strategies write the same code regardless of which tier the type lives in.

### 4.2 Component map

```
┌──────────────────────────────────────────────────────────────────────┐
│                          ENGINE (in-process)                          │
│                                                                       │
│   ┌──────────┐   Signal    ┌──────────────┐                           │
│   │ Strategy │────────────►│ TradingPipeline                          │
│   └──────────┘             │  - signal → OrderRequest                 │
│         ▲                  │  - risk gating                           │
│         │ OrderEvent       │  - hand to OrderManager                  │
│         │ (optional)       └──────┬───────────────────────┐           │
│         │                         │                       │           │
│         │                         ▼                       │           │
│   ┌──────────────────────────────────────┐                │           │
│   │           OrderManager               │                │           │
│   │                                      │                │           │
│   │  • lifecycle (state machine)         │                │           │
│   │  • pending list (Tier 2 fallback,    │                │           │
│   │    Tier 3 always)                    │                │           │
│   │  • triggers (Stop/Trail/IfTouched)   │                │           │
│   │  • OCO sibling cancel choreography   │                │           │
│   │  • OTO parent→child activation       │                │           │
│   │  • ClientOrderId ↔ BrokerOrderId map │                │           │
│   │  • subscribes to ticks (in-engine)   │                │           │
│   └──────┬───────────────────────────────┘                │           │
│          │ Tier 1 (or Tier 2 broker-native)               │           │
│          ▼                                                │           │
│   ┌──────────────────────────────────────┐                │           │
│   │              EventBus                │                │           │
│   └──────┬─────────────────┬─────────────┘                │           │
│          │                 │                              │           │
│          ▼                 ▼                              │           │
│   ┌────────────┐    ┌────────────┐                        │           │
│   │ Position   │    │ PnL        │                        │           │
│   │ Tracker    │    │ Calculator │                        │           │
│   └────────────┘    └────────────┘                        │           │
└─────────────────┼─────────────────────────────────────────┼───────────┘
                  │ broker.submit / broker.cancel           ▲
                  ▼ (Tier 1 + supported Tier 2)             │ events
                                                            │
┌──────────────────────────────────────────────────────────────────────┐
│                              BROKER                                   │
│                                                                       │
│  interface Broker {                                                   │
│      val capabilities: Set<OrderTypeCapability>                       │
│      fun submit(req: OrderRequest): SubmitAck                         │
│      fun cancel(orderId: String)                                      │
│  }                                                                    │
│                                                                       │
│  Concrete impls (Phase 7d ships LogBroker only):                      │
│  ┌─────────────┐  ┌─────────────────┐  ┌─────────────────────┐        │
│  │ LogBroker   │  │ PaperBroker     │  │ AlpacaBroker (7e+)  │        │
│  │ logs only   │  │ in-process fills│  │ real WS+REST        │        │
│  │ no fills    │  │ (refactor of    │  │                     │        │
│  │             │  │  MockBroker)    │  │                     │        │
│  └─────────────┘  └─────────────────┘  └─────────────────────┘        │
│                                                                       │
│  Broker emits events into the EventBus:                               │
│      OrderAccepted, OrderRejected, OrderPartiallyFilled,              │
│      OrderFilled, OrderCancelled                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. Order type taxonomy

```kotlin
package com.qkt.execution

sealed interface OrderRequest {
    val id: String                    // ClientOrderId — engine-assigned
    val symbol: String
    val side: Side
    val quantity: BigDecimal
    val timeInForce: TimeInForce
    val timestamp: Long

    // ──────────────────────────────────────────────────────
    // Tier 1 — broker primitives
    // ──────────────────────────────────────────────────────

    data class Market(...) : OrderRequest

    data class Limit(
        val limitPrice: BigDecimal,
        ...
    ) : OrderRequest

    // ──────────────────────────────────────────────────────
    // Tier 2 — broker-native if supported, engine fallback otherwise
    // ──────────────────────────────────────────────────────

    data class Stop(
        val stopPrice: BigDecimal,
        ...
    ) : OrderRequest

    data class StopLimit(
        val stopPrice: BigDecimal,
        val limitPrice: BigDecimal,
        ...
    ) : OrderRequest

    data class IfTouched(
        val triggerPrice: BigDecimal,
        val onTrigger: TriggerType,         // MARKET | LIMIT
        val limitPrice: BigDecimal? = null,
        ...
    ) : OrderRequest

    data class Bracket(
        val entry: OrderRequest,            // Market or Limit
        val takeProfit: BigDecimal,
        val stopLoss: BigDecimal,
        ...
    ) : OrderRequest

    // ──────────────────────────────────────────────────────
    // Tier 3 — always engine-side
    // ──────────────────────────────────────────────────────

    data class TrailingStop(
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,           // ABSOLUTE | PERCENT
        ...
    ) : OrderRequest

    data class TrailingStopLimit(
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        val limitOffset: BigDecimal,
        ...
    ) : OrderRequest

    data class StandaloneOCO(
        val leg1: OrderRequest,             // typically Limit or Stop
        val leg2: OrderRequest,
        ...
    ) : OrderRequest

    data class OTO(
        val parent: OrderRequest,
        val children: List<OrderRequest>,
        ...
    ) : OrderRequest

    data class ScaleOut(
        val basis: OrderRequest,            // entry order this scales out of
        val legs: List<ScaleOutLeg>,        // (priceTarget, fraction)
        ...
    ) : OrderRequest

    data class TimeExit(
        val target: OrderRequest,           // order to cancel/replace
        val deadline: Instant,
        val onExpiry: ExpiryAction,         // CANCEL | CLOSE_AT_MARKET
        ...
    ) : OrderRequest

    data class RegimeSwitch(
        val condition: () -> Boolean,
        val onTrue: List<OrderRequest>,
        val onFalse: List<OrderRequest>,
        ...
    ) : OrderRequest
}

enum class TimeInForce { DAY, GTC, IOC, FOK }
enum class TrailMode { ABSOLUTE, PERCENT }
enum class TriggerType { MARKET, LIMIT }
enum class ExpiryAction { CANCEL, CLOSE_AT_MARKET }

data class ScaleOutLeg(val priceTarget: BigDecimal, val fraction: BigDecimal)
```

Notes:

- `ScaleOut`, `TimeExit`, `RegimeSwitch` may evolve as strategies use them. They're listed for completeness; their final field shapes may shift during implementation. The plan can defer any of them if scope creep threatens.
- `Bracket` is sugar for `OTO(parent, children = [OCO(takeProfitLimit, stopLossStop)])`. We expose it as a top-level type because brokers natively support "bracket order" as a single concept; the engine's fallback synthesizes via OTO+OCO.
- `RegimeSwitch` is recommended for deferral — see section 20 #1.
- `TimeInForce` defaults vary by symbol class: stocks default DAY, FX/crypto default GTC. The default policy lives on the strategy/engine side, not on `OrderRequest`.

### 5.1 Composability — recursive trees

The taxonomy is a tree:

- **Branches** (hold children): `OTO`, `StandaloneOCO`, `Bracket`, `ScaleOut`, `TimeExit`. Each branch's children-typed fields (`children: List<OrderRequest>`, `leg1/leg2: OrderRequest`, etc.) accept **any** `OrderRequest` subtype, including other branches. Arbitrary depth.
- **Leaves** (terminal): `Market`, `Limit`, `Stop`, `StopLimit`, `IfTouched`, `TrailingStop`, `TrailingStopLimit`. No children fields.

The OrderManager MUST walk the tree recursively:
- When a parent fills, activate every child (children are themselves arbitrary subtrees).
- When an `OCO` leg fills, cancel the sibling **and recursively cancel any pending descendants of that sibling**.
- When a `TimeExit` deadline expires, recursively cancel every still-pending order in the target subtree.
- Trigger evaluation (Stop fallback, TrailingStop, IfTouched) applies to leaves wherever they sit in the tree.

Concrete shapes the design supports without special cases:

```kotlin
// 1. Trailing stop after a limit entry
OTO(
    parent   = Limit(BUY, qty=1.0, limitPrice=4500),
    children = listOf(TrailingStop(SELL, qty=1.0, trailAmount=50, mode=ABSOLUTE)),
)

// 2. Two competing entries, each with its own bracket
StandaloneOCO(
    leg1 = Bracket(entry=Limit(BUY, ..., 4500), takeProfit=4600, stopLoss=4450),
    leg2 = Bracket(entry=Limit(SELL, ..., 4700), takeProfit=4600, stopLoss=4750),
)

// 3. Scale-out with trailing on the remainder
OTO(
    parent   = Market(BUY, qty=3.0),
    children = listOf(
        IfTouched(SELL, qty=1.0, triggerPrice=84_000, onTrigger=MARKET),
        IfTouched(SELL, qty=1.0, triggerPrice=86_000, onTrigger=MARKET),
        TrailingStop(SELL, qty=1.0, trailAmount=500, mode=ABSOLUTE),
    ),
)

// 4. Time-bounded entry that auto-exits if not filled
TimeExit(
    target   = Limit(BUY, qty=1.0, limitPrice=80_000),
    deadline = Instant.now().plus(Duration.ofMinutes(10)),
    onExpiry = ExpiryAction.CANCEL,
)
```

### 5.2 Where conditioning lives

Three layers, used together:

| Condition runs… | Mechanism |
|---|---|
| **Before submission** ("only buy if VIX < 30") | Strategy code — branch in `onTickWithContext`, emit a different `OrderRequest`. Default. |
| **After submission, on order-tree state** ("when entry fills, activate child"; "when sibling fills, cancel other") | OrderManager choreography (OTO, OCO, ScaleOut, TimeExit). |
| **Continuously, on market state** ("trail my stop", "fire when price touches X") | OrderManager triggers (TrailingStop, IfTouched, Stop fallback). |

Strategy-side conditioning is the canonical place for "should I emit this order at all". The order-tree types express "given that I emitted it, here's how it behaves under choreographed conditions". This split keeps strategies simple and the order-tree types compositionally pure.

---

## 6. Broker interface

```kotlin
package com.qkt.broker

import com.qkt.execution.OrderRequest

interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>

    fun submit(request: OrderRequest): SubmitAck
    fun cancel(orderId: String)
    fun modify(orderId: String, changes: OrderModification): SubmitAck   // optional; throws UnsupportedOperationException if !MODIFY in caps
}

enum class OrderTypeCapability {
    MARKET,           // every broker
    LIMIT,            // every broker
    STOP,
    STOP_LIMIT,
    BRACKET,
    IF_TOUCHED,
    MODIFY,           // can modify in place vs cancel+resubmit
}

data class SubmitAck(
    val clientOrderId: String,
    val brokerOrderId: String?,         // null until the broker assigns one
    val accepted: Boolean,              // false = synchronously rejected at submission time
    val rejectReason: String? = null,
)

data class OrderModification(
    val newQuantity: BigDecimal? = null,
    val newLimitPrice: BigDecimal? = null,
    val newStopPrice: BigDecimal? = null,
)
```

Brokers emit events into the existing `EventBus`:

```kotlin
package com.qkt.execution.events

import com.qkt.bus.Event

sealed interface BrokerEvent : Event {
    val clientOrderId: String
    val brokerOrderId: String?
    val timestamp: Long

    data class OrderAccepted(...) : BrokerEvent
    data class OrderRejected(val reason: String, ...) : BrokerEvent
    data class OrderPartiallyFilled(val price: BigDecimal, val quantity: BigDecimal, val cumulativeFilled: BigDecimal, ...) : BrokerEvent
    data class OrderFilled(val price: BigDecimal, val quantity: BigDecimal, ...) : BrokerEvent
    data class OrderCancelled(val reason: String, ...) : BrokerEvent
}
```

The `OrderFilled` event carries everything `Trade` carries today; `Trade` is derived from it for `PositionTracker` consumption.

---

## 7. OrderManager

The OrderManager is the engine-side owner of order lifecycle. Single responsibilities:

1. **Receive** `OrderRequest`s from the pipeline.
2. **Dispatch** by order type and broker capabilities (Tier 1/2/3 logic).
3. **Hold pending** for the engine-synthesized cases (Tier 3 always, Tier 2 fallback).
4. **Evaluate triggers** on every tick — for TrailingStop, Stop fallback, IfTouched fallback, etc.
5. **Choreograph composites** — OCO sibling cancel-on-fill, OTO parent-fill→child-activation, ScaleOut leg sequencing.
6. **Subscribe** to `BrokerEvent`s on the bus and update internal order state.
7. **Maintain ID mapping** between `clientOrderId` (engine-assigned, stable) and `brokerOrderId` (assigned by broker, used for cancel calls).
8. **Expose query APIs** for the pipeline / risk engine / introspection (`getOrder(id)`, `activeOrders()`, `pendingOrders()`).

Outline:

```kotlin
class OrderManager(
    private val broker: Broker,
    private val bus: EventBus,
    private val priceProvider: MarketPriceProvider,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()
    private val triggers: MutableList<Trigger> = mutableListOf()

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> ... }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> ... }
        bus.subscribe<BrokerEvent.OrderFilled>   { e -> ... }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> ... }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> ... }
        bus.subscribe<TickEvent> { e -> evaluateTriggers(e.tick) }
    }

    fun submit(request: OrderRequest) { ... dispatch logic ... }
    fun cancel(clientOrderId: String) { ... }
    fun modify(clientOrderId: String, changes: OrderModification) { ... }

    fun getOrder(clientOrderId: String): ManagedOrder?
    fun activeOrders(): List<ManagedOrder>
    fun pendingOrders(): List<ManagedOrder>

    private fun evaluateTriggers(tick: Tick) { ... }
    private fun synthesize(req: OrderRequest) { ... }
}

data class ManagedOrder(
    val request: OrderRequest,
    val state: OrderState,
    val brokerOrderId: String?,
    val cumulativeFilledQuantity: BigDecimal,
    val avgFillPrice: BigDecimal?,
    val children: List<ManagedOrder>,        // for OCO/OTO
    val parentClientOrderId: String?,
    val createdAt: Long,
    val lastUpdatedAt: Long,
)

enum class OrderState {
    CREATED, PENDING, SUBMITTED, WORKING,
    PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED,
}
```

---

## 8. Order state machine

```
                     ┌──────────┐
                     │ CREATED  │  in memory only; not yet handed in
                     └────┬─────┘
                          │  submit()
                          ▼
                   ┌──────────────┐
                   │   PENDING    │  Tier 2 fallback or Tier 3; awaiting trigger
                   └──┬────────┬──┘
                      │        │ cancel()
            trigger   │        ▼
                      │   ┌──────────┐
                      │   │CANCELLED │  (terminal)
                      │   └──────────┘
                      ▼
              ┌──────────────┐
              │  SUBMITTED   │  sent to broker; awaiting Accepted
              └──┬─────────┬─┘
                 │         │  OrderRejected
                 │         ▼
                 │   ┌──────────┐
                 │   │ REJECTED │  (terminal)
                 │   └──────────┘
                 │
                 │ OrderAccepted
                 ▼
          ┌──────────────┐
          │   WORKING    │  broker has it in book / matching
          └──┬─────┬───┬─┘
             │     │   │ cancel() → broker.cancel(...) → OrderCancelled
             │     │   ▼
             │     │  ┌──────────┐
             │     │  │CANCELLED │
             │     │  └──────────┘
             │     │
             │     │ OrderPartiallyFilled
             │     ▼
             │  ┌──────────────────┐
             │  │ PARTIALLY_FILLED │ ──── more partials ────┐
             │  └──┬───────────────┘                        │
             │     │                                         │
             │     │ remaining=0  (final partial = OrderFilled)
             │ OrderFilled (full)                            │
             ▼     ▼                                          │
          ┌──────────────┐ ◄──────────────────────────────┘
          │    FILLED    │  (terminal)
          └──────────────┘
```

OCO/OTO/ScaleOut state propagates to children:
- OTO: parent → FILLED triggers children CREATED → PENDING/SUBMITTED.
- OCO: any leg → FILLED triggers sibling cancel.
- ScaleOut: each leg fires when its price target hits; remaining quantity tracked.

---

## 9. Engine-managed vs broker-managed responsibilities

| Concern | Engine | Broker |
|---|---|---|
| Receiving the strategy's intent (`OrderRequest`) | ✓ pipeline → OrderManager | — |
| Selecting handling tier (1/2/3) | ✓ OrderManager dispatch | — |
| Trigger evaluation for engine-synthesized types | ✓ OrderManager via tick subscription | — |
| Holding limit orders in the book and matching | — | ✓ broker (always) |
| Holding stops server-side (when supported) | — | ✓ broker (when capability advertised) |
| Composite choreography (OCO, OTO, ScaleOut) | ✓ OrderManager | — |
| ClientOrderId ↔ BrokerOrderId mapping | ✓ OrderManager | broker assigns its own id and returns it |
| Cancel of pending engine-side order | ✓ OrderManager (no broker call) | — |
| Cancel of working broker-side order | engine asks | ✓ broker performs |
| Position tracking (canonical) | ✓ engine `PositionTracker` (from `OrderFilled` events) | — |
| Position reporting (broker view, used for reconciliation in 7e+) | reads in 7e+ | ✓ broker provides |
| PnL computation | ✓ `PnLCalculator` | — |
| Risk gating before submit | ✓ `RiskEngine` | — |
| Order persistence across engine restarts | ✗ deferred to 7e+ | varies per broker |
| Network protocol / auth / reconnect | — | ✓ broker |

---

## 10. Worked flow examples

### 10.1 Plain Market buy

```
Strategy emits Signal.Buy("EURUSD", 1.0)
   ↓
TradingPipeline → OrderRequest.Market(id=A, EURUSD, BUY, 1.0)
   ↓
OrderManager.submit(A)
   • Tier 1 → broker.submit(A)
   • orders[A].state = SUBMITTED
   ↓
broker.submit(A) returns SubmitAck(brokerId=X, accepted=true)
bus ← BrokerEvent.OrderAccepted(A, X, ts)
   ↓
OrderManager: orders[A].state = WORKING; brokerOrderId=X
   ↓
PaperBroker (inline) | AlpacaBroker (WS, later):
bus ← BrokerEvent.OrderFilled(A, X, price=1.10, qty=1.0, ts)
   ↓
OrderManager: orders[A].state = FILLED; cumulativeFilledQuantity = 1.0; avgFillPrice = 1.10
PositionTracker.applyFill(EURUSD, BUY, 1.0, 1.10)
PnLCalculator.recompute()
```

### 10.2 Bracket: entry Limit + take-profit + stop-loss

For a broker that advertises `BRACKET`:

```
Strategy intent:
  buy XAUUSD at 4500 limit; take profit at 4600; stop loss at 4450

Pipeline: OrderRequest.Bracket(entry = Limit(4500), takeProfit = 4600, stopLoss = 4450, id=B)

OrderManager.submit(B)
  broker.capabilities contains BRACKET → ship whole:
    broker.submit(B) → SubmitAck
  bus ← OrderAccepted(B, ...)

[gold drops to 4500]
  bus ← OrderFilled(B-entry, ...)   // broker-side bracket reports child fills under their own ids
PositionTracker.applyFill(...)

[gold rises to 4600]
  bus ← OrderFilled(B-tp, ...)      // take-profit child filled, server-side cancels stop-loss
  bus ← OrderCancelled(B-sl, reason="OCO sibling filled")
Position closed. Bracket order group terminal.
```

For a broker without `BRACKET` capability:

```
OrderManager.submit(B)
  no BRACKET cap → synthesize as OTO(parent=Limit(4500), children=[OCO(Limit(4600), Stop(4450))])
  step 1: submit parent Limit(4500) to broker. children PENDING.
  bus ← OrderAccepted(parent, ...)

[gold drops to 4500]
  bus ← OrderFilled(parent, ...)
  OrderManager (OTO logic): activate children
    Limit(4600) → broker.submit (Tier 1) → SUBMITTED → ACCEPTED → WORKING (sits at broker)
    Stop(4450)  → check Stop capability:
                    if broker has STOP cap → broker.submit
                    else → engine PENDING; OrderManager watches ticks

[gold rises to 4600]
  bus ← OrderFilled(tp-leg, ...)
  OrderManager (OCO): cancel sibling stop-loss
    if stop is broker-side → broker.cancel(stop)
    if stop is engine-pending → mark CANCELLED in OrderManager (no broker call)
```

Strategy and PositionTracker see identical event sequences in both cases.

### 10.3 TrailingStop (always engine)

```
Strategy: TrailingStop(SELL, qty=1.0, trailAmount=0.005, trailMode=ABSOLUTE)
OrderManager: PENDING; HWM tracker initialized to current price (1.10)
                                                 stop level = 1.095

Tick 1.105 → HWM=1.105, stop=1.100  (ratchet up)
Tick 1.108 → HWM=1.108, stop=1.103
Tick 1.106 → HWM unchanged, stop unchanged (ratchet only up)
Tick 1.102 → trigger! (price ≤ stop level)
   OrderManager: synthesize a Market(SELL, 1.0) → broker.submit
   bus ← OrderAccepted, then OrderFilled
PositionTracker updated. Trailing order group terminal.
```

### 10.4 Standalone OCO of two pending entries (always engine)

```
Strategy: "buy BTC at 80,000 OR sell BTC at 90,000, whichever comes first"
  StandaloneOCO(leg1=Limit(BUY, 80_000), leg2=Limit(SELL, 90_000))

OrderManager: both Limits go to the broker (book-resident, getting good fills).
              both orders are tagged with an OCO group id internally.

[BTC drops to 80,000]
  bus ← OrderFilled(leg1, ...)
  OrderManager: cancel sibling
    broker.cancel(leg2)
  bus ← OrderCancelled(leg2)

Position long 1 BTC. OCO group terminal.
```

### 10.5 Cancel of a pending TrailingStop

```
OrderManager state: TrailingStop is PENDING (never went to broker).
Engine calls OrderManager.cancel(R)
  R is PENDING → no broker call needed
  R.state = CANCELLED
  bus ← BrokerEvent.OrderCancelled(R, reason="user cancel")

PositionTracker is unaffected (no fill). PnL unchanged.
```

---

## 11. Reference implementation: `LogBroker`

`LogBroker` is the only broker shipped in 7d. It exists for strategy testing without execution semantics.

- `capabilities` = `{MARKET, LIMIT, STOP, STOP_LIMIT, BRACKET, IF_TOUCHED}` — claims everything sensible because it never has to actually execute.
- `submit(req)`:
  - Logs the full request at INFO.
  - Emits `BrokerEvent.OrderAccepted(clientOrderId, brokerOrderId = req.id, accepted = true)` synchronously into the bus.
  - Returns `SubmitAck(req.id, req.id, accepted = true)`.
  - **Never emits a fill.** The order sits in WORKING state until cancelled.
- `cancel(clientOrderId)`:
  - Logs at INFO.
  - Emits `BrokerEvent.OrderCancelled(clientOrderId, brokerOrderId, reason="user cancel")`.
- `modify(clientOrderId, changes)`:
  - Logs at INFO.
  - Emits `BrokerEvent.OrderAccepted` for the modified state (no-op semantics — log broker doesn't enforce changes).

This makes `LogBroker` ideal for:
- Verifying strategy logic generates the right `OrderRequest`s.
- Testing OCO / OTO choreography end-to-end without fill simulation interfering.
- The audit/demo runners (Phase 7e Phase tests) — strategy → broker without contaminating PaperBroker semantics.

---

## 12. PaperBroker (refactor of `MockBroker`)

`MockBroker` becomes `PaperBroker`. Same fill logic; new event-emitting interface.

- `capabilities` = `{MARKET, LIMIT, STOP, STOP_LIMIT, BRACKET, IF_TOUCHED}` — simulates each natively, in-process.
- Fill semantics:
  - `Market` fills inline at `priceProvider.lastPrice(symbol)` if available; else emits `OrderRejected("no price")`.
  - `Limit` sits in PaperBroker's in-process per-symbol queue of working limits; fills when an incoming tick crosses the limit price (BUY: tick ≤ limit; SELL: tick ≥ limit). Multiple limits per symbol are allowed; first-submitted fills first when multiple cross simultaneously.
  - `Stop` / `StopLimit` triggers when a tick crosses the stop price; converts to an internal Market or Limit at trigger time.
  - `Bracket` decomposes into entry + OCO children inside PaperBroker (server-side analogue): entry fills → children activate; either child fills → sibling cancelled.
- All fills emitted as `OrderFilled` events on the bus; never via return value.
- Backtest determinism preserved: PaperBroker processes ticks in the same thread as the engine; no concurrency.

`MockBroker` is removed. All references migrate to `PaperBroker`. (No backwards compatibility — Phase 7d is permitted to break callers per the qkt skill §7 "no backwards compatibility cruft".)

---

## 13. TradingPipeline migration

Current pipeline calls `broker.execute(...)` synchronously and applies the return:

```kotlin
val trade = broker.execute(order) ?: return
positions.apply(trade)
pnl.recompute()
```

After 7d:

```kotlin
// At pipeline construction:
bus.subscribe<BrokerEvent.OrderFilled> { e ->
    positions.applyFill(e)
    pnl.recompute()
}
bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
    positions.applyFill(e)
    pnl.recompute()
}
bus.subscribe<BrokerEvent.OrderRejected> { e ->
    log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
}

// At signal emission:
val request = orderFactory.fromSignal(signal)
val riskDecision = riskEngine.evaluate(request, positions)
when (riskDecision) {
    is Decision.Approve -> orderManager.submit(request)
    is Decision.Reject  -> log.warn("Risk rejected: $request reason=${riskDecision.reason}")
}
```

`PositionTracker.applyFill(BrokerEvent.OrderFilled)` is the new entry point; the old `apply(Trade)` becomes a thin adapter.

`Trade` remains as the persistent record of an executed fill (used in `recentTrades()`, audit reports, etc.) but is constructed from `BrokerEvent.OrderFilled` rather than returned by `broker.execute`.

---

## 14. Risk integration

`RiskEngine` evaluates `OrderRequest`s before the OrderManager submits them. Same gate point as today.

For composite orders (`Bracket`, `OTO`, `OCO`), risk evaluates the **net effect** of the entry leg on positions. Children (TP/SL) don't need to be risk-checked because they unwind, not grow, the position.

`MaxPositionSize` and other rules operate on `OrderRequest` rather than `Order`. The `Order` class as an in-memory mutable record is removed; `OrderRequest` is the immutable submission, and `ManagedOrder` (held in OrderManager) is the stateful view.

---

## 15. Backtest determinism

Backtest uses `PaperBroker`. Fills happen synchronously on the engine thread inside `broker.submit(...)` — PaperBroker emits the event via `bus.publish(...)` before returning. Same thread, deterministic order of events.

The `OrderManager`'s tick subscription processes triggers in the same thread on each tick; no race with broker events.

Engine-synthesized triggers (Stop fallback, TrailingStop, etc.) fire deterministically on tick boundaries: the OrderManager evaluates triggers AFTER all strategies have processed the tick, so triggers see consistent state.

Multi-run determinism: same input ticks → same orders → same fills → same trades. Asserted by existing Phase 4 / 7b backtest tests.

---

## 16. Migration plan (breaking changes)

Affected files (estimate):

- `src/main/kotlin/com/qkt/broker/Broker.kt` — interface rewritten
- `src/main/kotlin/com/qkt/broker/MockBroker.kt` → `PaperBroker.kt` — rewrite
- `src/main/kotlin/com/qkt/broker/LogBroker.kt` — new
- `src/main/kotlin/com/qkt/execution/OrderRequest.kt` — new sealed hierarchy
- `src/main/kotlin/com/qkt/execution/OrderType.kt` — removed (folded into sealed types)
- `src/main/kotlin/com/qkt/execution/Order.kt` — removed (replaced by `OrderRequest` + `ManagedOrder`)
- `src/main/kotlin/com/qkt/execution/OrderFactory.kt` — rewrites: `signal → OrderRequest`
- `src/main/kotlin/com/qkt/execution/Trade.kt` — kept; constructed from `OrderFilled`
- `src/main/kotlin/com/qkt/execution/events/BrokerEvent.kt` — new
- `src/main/kotlin/com/qkt/execution/OrderManager.kt` — new (~300-400 LOC)
- `src/main/kotlin/com/qkt/execution/OrderState.kt` — new
- `src/main/kotlin/com/qkt/execution/Trigger.kt` (and impls per type) — new
- `src/main/kotlin/com/qkt/positions/PositionTracker.kt` — `applyFill(OrderFilled)` method, old `apply(Trade)` becomes adapter
- `src/main/kotlin/com/qkt/risk/RiskRule.kt` — operates on `OrderRequest`
- `src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt` — refactor for `OrderRequest`
- `src/main/kotlin/com/qkt/app/TradingPipeline.kt` — rewires for events
- `src/main/kotlin/com/qkt/app/Backtest.kt` — minor (still uses pipeline)
- `src/main/kotlin/com/qkt/app/LiveSession.kt` — minor (still uses pipeline)

All `MockBroker` test references migrate to `PaperBroker`. All `Order(...)` constructions migrate to `OrderRequest.X(...)`. Risk rules updated.

Estimated: ~600 LOC production new, ~300 LOC production refactor, ~500 LOC test diff. About 1.5× Phase 7c.

---

## 17. Testing strategy

- **Order type unit tests** — each `OrderRequest` subtype: construction, validation (`require` clauses), serialization invariants.
- **OrderManager unit tests** — per-trigger correctness:
  - Stop fallback fires on price cross.
  - StopLimit fallback fires + submits Limit at trigger.
  - TrailingStop ratchets up only.
  - OCO sibling cancellation.
  - OTO parent fill activates children.
  - ScaleOut leg sequencing.
- **PaperBroker tests** — fill semantics for each capability, in-process determinism.
- **LogBroker tests** — emits OrderAccepted; never emits fill; cancel emits OrderCancelled.
- **Pipeline integration tests** — strategy → OrderManager → PaperBroker → bus → PositionTracker, full round trip.
- **Backtest determinism tests** — same fixture → same trade list across two runs.
- **Risk gating tests** — rejection prevents OrderManager from receiving the request.

Test fakes:
- `FakeBroker` (test-side) — implements `Broker` with explicit programmable capabilities and event emission, used for OrderManager unit tests.

---

## 18. Acceptance criteria

- [ ] `OrderRequest` sealed type ships with all listed subtypes.
- [ ] `Broker` interface ships with `capabilities`, `submit`, `cancel`, `modify`.
- [ ] `BrokerEvent` sealed event hierarchy ships and is published on `EventBus`.
- [ ] `OrderManager` ships with full state machine and dispatch logic.
- [ ] `LogBroker` ships and is the canonical 7d reference impl.
- [ ] `PaperBroker` replaces `MockBroker` with same fill semantics, now event-emitting.
- [ ] `PositionTracker` updates from `OrderFilled` events, not return values.
- [ ] `RiskEngine` operates on `OrderRequest`.
- [ ] All Phase 7c tests pass after migration.
- [ ] New tests cover: each `OrderRequest` type, each Trigger, OCO/OTO/ScaleOut choreography, LogBroker, PaperBroker, pipeline integration.
- [ ] `MaxAudit` runner continues to work (log-only outputs replace MockBroker fills).
- [ ] Backtest determinism preserved across two runs of the same fixture.
- [ ] Phase changelog at `docs/phases/phase-7d-broker-and-orders.md`.

---

## 19. Out of scope (deferred to 7e+ or later)

- Real broker integrations (Alpaca, IBKR, OANDA, Binance).
- Server-side order persistence guarantees & idempotent reconnect (`ClientOrderId` reuse on reconnect; broker-side dedup).
- Position reconciliation (broker view ↔ engine view — what to do on mismatch).
- Margin / buying-power / equity reporting from brokers.
- Commission, spread, slippage modeling — listed in the post-7d roadmap.
- Multi-account support (one broker, multiple accounts).
- Sub-second / microsecond timing precision (Phase 7d uses `Long` ms timestamps as before).
- Order modification semantics (`modify` is in the interface but `LogBroker` and `PaperBroker` may treat it as cancel+resubmit; in-place modify is broker-specific).
- DSL-level expression of order types (Phase 8).

---

## 20. Open questions / spec ambiguities

These are flagged for the implementation plan to resolve.

1. **`RegimeSwitch` deferral (recommended).** The type covers ground that strategy-side branching already handles cleanly. Recommendation: **drop from 7d**, revisit in Phase 8 where the DSL can express conditional order trees declaratively. The plan should remove `RegimeSwitch` unless a concrete 7d strategy needs it.

2. **`ScaleOut` semantics on partial parent fills.** If the entry leg of a ScaleOut partially fills, do the legs scale to the actual filled quantity or to the requested quantity? Plan picks one and documents.

3. **`TimeExit` deadline source.** Wall clock vs market session clock vs strategy-driven? Plan defaults to wall clock; documents.

4. **Modify-in-place vs cancel-and-resubmit.** Some brokers support `modify`; some don't. Plan decides whether OrderManager exposes `modify(...)` to strategies, or whether it always implements modify as cancel+new (more portable, less efficient). Recommendation: cancel+new in 7d; in-place modify when a real broker that supports it lands.

5. **`Bracket` representation in OrderManager.** Decompose to OTO+OCO at submit time, or keep as a first-class entity? Plan picks. Recommendation: first-class for brokers that advertise `BRACKET`; decompose internally for fallback.

6. **Risk evaluation of composite orders.** Risk evaluates the parent only; assumes children unwind. Edge case: ScaleOut legs increase position size if pyramiding. Plan documents.

7. **Backtest concurrency.** PaperBroker emits events synchronously inline. Confirm: no race between `OrderManager.evaluateTriggers(tick)` and `bus.publish(OrderFilled)` from PaperBroker — both run on the engine thread sequentially. Plan adds an integration test that asserts this sequencing.

8. **`OrderRequest.id` generation.** Strategy provides? Pipeline assigns? OrderManager assigns? Recommendation: Pipeline assigns from `IdGenerator` at request construction; OrderManager treats it as opaque. Plan documents.

9. **Symbol validation at submit time.** Should OrderManager reject orders for symbols the broker hasn't quoted? Recommendation: no — broker's `submit` returns `SubmitAck(accepted=false)` if it can't handle the symbol. Plan documents.

10. **Multi-broker dispatch.** Spec assumes one broker. Multi-broker routing (e.g., Alpaca for stocks + Binance for crypto) is logically a `CompositeBroker` analogous to `CompositeMarketSource`. Phase 7d does not implement it; its absence is acceptable because LogBroker handles every symbol. Plan documents the deferred shape.

---

## References

- Phase 7 spec: [`2026-05-05-trading-engine-phase7-design.md`](./2026-05-05-trading-engine-phase7-design.md)
- Phase 7 changelog: [`../phases/phase-7-live-runtime.md`](../phases/phase-7-live-runtime.md)
- Roadmap (post-Phase-7 priorities): `tt.txt` at repo root (sketch, will be folded into a dedicated planning doc)
- qkt skill (conventions): [`.claude/skills/qkt/SKILL.md`](../../.claude/skills/qkt/SKILL.md)
