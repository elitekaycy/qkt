# qkt — Trading Engine Phase 3 Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 3 of the qkt trading platform. Adds a pluggable risk-rule engine that gates between SignalEvent and OrderEvent, plus a position tracker that consumes TradeEvent. Two built-in rules ship: MaxPositionSize and MaxOpenPositions. P&L and BigDecimal migration are deferred to Phase 3b.
**Phase 2b baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase2b-design.md`](2026-05-03-trading-engine-phase2b-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. Phase 1 shipped the core engine. Phase 2a shipped the event bus + multi-strategy + SLF4J. Phase 2b shipped the candle aggregator. Phase 3 ships the risk + positions layer.

The Phase 5 DSL endgame — `RISK MAX_POSITION_SIZE 5; WHEN EMA(9) > EMA(21) BUY XAUUSD 1` — informs Phase 3's design. Risk rules are composable, configurable, extensible. Positions are queryable through a stable read interface. Both layers are designed so the DSL compiler in Phase 5 can target them directly without runtime shims.

This document is **Phase 3 only**.

## 2. Phase 3 — what we are building

A risk-gating layer between strategy decisions and order execution, plus a position tracker that maintains running per-symbol holdings. User-visible behavior: `./gradlew run` shows mixed `FILLED:` and `REJECTED:` log lines as the cap kicks in.

Concretely:

1. New `Position(symbol, quantity)` data class (no avgEntryPrice yet).
2. New `PositionProvider` read interface and `PositionTracker` concrete impl. `apply(trade)` updates running quantity per symbol; zero-quantity entries removed.
3. New `RiskRule` interface (`evaluate(order, positions): Decision`) and `Decision` sealed class (`Approve` | `Reject(reason)`).
4. New `RiskEngine(rules, positions)` that evaluates rules in order — first reject wins.
5. Two built-in rules: `MaxPositionSize(symbol, maxQty)` and `MaxOpenPositions(maxCount)`.
6. New `RiskRejectedEvent(order, reason)` variant of the `Event` sealed class.
7. The SignalEvent subscriber in `main()` is extended: builds the candidate Order, calls `riskEngine.approve(order)`, publishes either `OrderEvent` or `RiskRejectedEvent` accordingly.
8. A `TradeEvent` subscriber `positions.apply(trade)` registered FIRST (before the FILLED logger reads positions).
9. Sample run uses `MaxPositionSize("XAUUSD", maxQty = 3.0)` so the demo emits ~3 fills + ~7 rejections.

End condition: 65 → 90 tests, all green. `./gradlew run` shows the risk gate in action. Phase 2b acceptance criteria still hold.

**Phase 3 does NOT include** (deferred):

- P&L (realized/unrealized) — Phase 3b
- `Position.avgEntryPrice` — Phase 3b
- `BigDecimal` migration — Phase 3b alongside P&L
- `BrokerRejectedEvent` (structured broker rejection) — real-broker phase
- `MaxNotionalValue`, `MaxDailyLoss`, `SymbolWhitelist`, time-of-day rules — add when needed
- Risk metrics, rejection counters — Phase 4+
- Position persistence — future
- Multi-account / portfolio — future
- Lot-by-lot tracking (FIFO/LIFO) — future
- Custom user-defined runtime rules — Phase 5 DSL
- Async / coroutines — post-Phase 5

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Phase 3 scope = Risk + Positions only.** P&L deferred. BigDecimal deferred. | Risk + positions are tightly coupled (risk needs positions); P&L is separable reporting; BigDecimal is justified only when math compounds (P&L). Tight scope, single PR cycle. |
| D2 | **Risk gate sits between SignalEvent and OrderEvent.** Rejected orders publish `RiskRejectedEvent` instead of `OrderEvent`. | Risk is about intent — the broker's job is to fill orders that exist. A rejected order shouldn't pollute the order-event log. Phase 4 backtest replay benefits from clean separation. |
| D3 | **`PositionTracker` is updated by an explicit `bus.subscribe<TradeEvent>` in `main()`.** No constructor side-effects. | Phase 2a established the pattern: trackers are state, the wiring layer subscribes. Mirrors `MarketPriceTracker`. Easier to test in isolation; flexible for replay scenarios. |
| D4 | **`Position(symbol, quantity)` only.** Zero-quantity entries removed from the map. `positionFor()` returns null if not held. | YAGNI. Phase 3 risk rules need only quantity. `avgEntryPrice` is anticipating Phase 3b (P&L) — speculative scaffolding. Adding it later costs one constructor parameter. |
| D5 | **Built-in rules: `MaxPositionSize` + `MaxOpenPositions`.** RiskEngine evaluates in order, first reject wins. | Two rules exercise the SPI as a list (composition) and use both `positionFor(symbol)` and `allPositions()` on the provider. Three+ rules anticipate Phase 5 DSL — defer. |
| D6 | **Two new packages: `com.qkt.positions` and `com.qkt.risk` (with `risk/rules/` subpackage).** | Positions are independently useful (P&L, DSL queries). Risk depends on positions; not the other way. Symmetric to existing `com.qkt.bus`, `com.qkt.events`, `com.qkt.candles` separation. |
| D7 | **`RiskRejectedEvent(order, reason: String)`.** IdGenerator increments per signal regardless of approval. | Order carries everything (id, symbol, side, qty, type, ts). Reason is human-readable string. Deterministic id sequence preserved across approvals + rejections. |
| D8 | **`RiskRule.evaluate(order: Order, positions: PositionProvider): Decision`.** No prices or clock parameters. | YAGNI. No Phase 3 rule needs prices or clock. Adding a parameter later is mechanical. Pure-function rule signature is easy to test and easy for the DSL to target. |
| D9 | **Broker rejections stay null (Phase 1 behavior).** Only risk publishes a rejection event. `Broker` interface unchanged. | Out of scope to refactor `Broker` for structured failures. Real-broker phase will introduce that. The "missing TradeEvent for OrderEvent" pattern is observable for backtest replay. |
| D10 | **`Main.kt` demo: `MaxPositionSize("XAUUSD", maxQty = 3.0)`.** ~3 FILLED + ~7 REJECTED in the run. FILLED log shows `(position: N.N)`. | Demonstrates the full risk story (approve, reject, position tracker) in one configuration. No new sample strategy needed. |
| D11 | **DSL-conscious shapes throughout.** `RiskRule` is composable. `PositionProvider` is a stable read interface. Configuration is data passed to the engine. | Phase 5 DSL compiles `RISK MAX_POSITION_SIZE 5` to `MaxPositionSize("...", 5.0)` and adds it to `RiskEngine`'s rules list. No DSL-specific shim in Phase 3. |

## 4. Architecture

### 4.1 Pipeline

```
                                                   ┌──────────────────────────┐
                                                   │       EventBus           │
                                                   └──────────────────────────┘
                                                    ▲    ▲    ▲    ▲    ▲    ▲
                                                    │    │    │    │    │    │ dispatch ▼
┌──────────┐ pull ┌─────────┐ TickEvent             │    │    │    │    │
│MockTickFd│─────▶│ Engine  │──────────────▶ aggregator + strategies (TickEvent)
└──────────┘     └─────────┘                              │
                                                          │ SignalEvent
                                                          ▼
                                          ┌────────────────────────────────────────┐
                                          │ subscriber: signal→order + risk gate   │
                                          │   1. order = signal.toOrder(...)       │
                                          │   2. decision = riskEngine.approve(o)  │
                                          │   3a. Approve → publish OrderEvent     │
                                          │   3b. Reject  → publish RiskRejected   │
                                          └────────────────────────────────────────┘
                                                          │       │
                                          OrderEvent      ▼       ▼  RiskRejectedEvent
                                                  ┌──────────┐  ┌─────────────────┐
                                                  │ broker   │  │ logger          │
                                                  │ handler  │  │ (REJECTED log)  │
                                                  └──────────┘  └─────────────────┘
                                                       │ TradeEvent
                                                       ▼
                                          ┌────────────────────────────────────────┐
                                          │ TradeEvent subscribers, in order:       │
                                          │   1. positions.apply(trade)  ← FIRST   │
                                          │   2. logger (FILLED + position log)    │
                                          └────────────────────────────────────────┘
```

### 4.2 One SignalEvent through the risk gate

Strategy emits `Signal.Buy("XAUUSD", 1.0)`. Aggregator and tracker have already done their work for the triggering tick. The signal arrives at the bus.

```
SignalEvent (Buy XAUUSD 1.0) arrives
└─ subscriber runs:
   1. id = idGenerator.next()                           // "ORD-7"
   2. order = signal.toOrder(id, clock.now())
   3. decision = riskEngine.approve(order)
        for each rule in rules:
          d = rule.evaluate(order, positions)
          if d is Reject: return d                      // first reject wins
        return Approve
   4a. if Approve: bus.publish(OrderEvent(order))
        → broker handler (depth-first dispatch):
            broker.execute(order) → Trade
            bus.publish(TradeEvent(trade))
              → positions.apply(trade)                   // FIRST trade subscriber
              → log.info("FILLED: ... position: N.N")
   4b. if Reject(reason): bus.publish(RiskRejectedEvent(order, reason))
        → log.info("REJECTED: ...")
```

The risk gate is one subscriber lambda — no new component class. `RiskEngine`, `PositionTracker`, and rule instances are constructed in `main()` and closed over by the subscriber.

### 4.3 Component dependencies

```
positions/ ──▶ marketdata, common, execution
risk/      ──▶ positions, execution, common
risk/rules/ ─▶ positions, execution
events/    ──▶ ... (gains RiskRejectedEvent — payload imports unchanged: Order is already imported)
app/       ──▶ ... positions, risk, risk/rules
```

Two new top-level packages. No cycles. `risk` depends on `positions`; `positions` doesn't know about `risk`.

### 4.4 What changes from Phase 2b

- `events/Event.kt` — adds `RiskRejectedEvent(order, reason)` variant.
- `bus/EventBus.kt` — `stamp` adds `is RiskRejectedEvent ->` branch (sealed-class enforced).
- `app/Main.kt` — wires `PositionTracker`, `RiskEngine` with rules, adds risk gate to SignalEvent subscriber, adds tracker-update TradeEvent subscriber FIRST, adds RiskRejectedEvent logger, FILLED logger reads position.
- `app/EndToEndTest.kt` — extends `wirePipeline` helper with optional `rules` parameter; adds `positions` field; adds 3 new tests.
- `bus/EventBusTest.kt` — adds 1 stamp test for RiskRejectedEvent.

### 4.5 What does NOT change

- `Tick`, `Candle`, `Order`, `OrderType`, `Trade`, `Side`, `Signal` data classes — unchanged.
- `Engine`, `MarketPriceTracker`, `MarketPriceProvider`, `Broker`, `MockBroker`, `Clock`, `IdGenerator`, `SequenceGenerator` — unchanged.
- `EventBus` (except stamp branch) — unchanged.
- `CandleAggregator`, `TimeWindow` — unchanged.
- `Strategy` interface — unchanged.
- `EveryNthTickBuyStrategy` — unchanged.
- `MockTickFeed` — unchanged.
- All Phase 1 + 2a + 2b tests — pass unchanged. (`EndToEndTest` is extended but existing 9 tests behave the same since the helper's new `rules` parameter defaults to empty.)

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/Main.kt                              # extended
├── broker/                                  # unchanged
├── bus/EventBus.kt                          # extended (stamp branch)
├── candles/                                 # unchanged
├── common/                                  # unchanged
├── engine/                                  # unchanged
├── events/Event.kt                          # extended (RiskRejectedEvent)
├── execution/                               # unchanged
├── marketdata/                              # unchanged
├── positions/                               # NEW
│   ├── Position.kt                          # NEW
│   └── PositionProvider.kt                  # NEW (interface + tracker)
├── risk/                                    # NEW
│   ├── RiskEngine.kt                        # NEW
│   ├── RiskRule.kt                          # NEW (interface + Decision sealed class)
│   └── rules/                               # NEW
│       ├── MaxPositionSize.kt               # NEW
│       └── MaxOpenPositions.kt              # NEW
└── strategy/                                # unchanged

src/test/kotlin/com/qkt/
├── app/EndToEndTest.kt                      # extended (+3 tests, helper extended)
├── broker/MockBrokerTest.kt                 # unchanged
├── bus/EventBusTest.kt                      # extended (+1 stamp test)
├── candles/                                 # unchanged
├── common/MonotonicSequenceGeneratorTest.kt # unchanged
├── engine/EngineTest.kt                     # unchanged
├── marketdata/                              # unchanged
├── positions/                               # NEW
│   └── PositionTrackerTest.kt               # NEW (~6 tests)
├── risk/                                    # NEW
│   ├── RiskEngineTest.kt                    # NEW (~5 tests)
│   └── rules/                               # NEW
│       ├── MaxPositionSizeTest.kt           # NEW (~5 tests)
│       └── MaxOpenPositionsTest.kt          # NEW (~5 tests)
└── strategy/                                # unchanged
```

Six new production files + five new test files.

## 6. Type & interface signatures

### 6.1 `positions.Position`

```kotlin
package com.qkt.positions

data class Position(
    val symbol: String,
    val quantity: Double,
)
```

### 6.2 `positions.PositionProvider`

```kotlin
package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.Trade

interface PositionProvider {
    fun positionFor(symbol: String): Position?
    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    private val positions = mutableMapOf<String, Position>()

    fun apply(trade: Trade) {
        val current = positions[trade.symbol]?.quantity ?: 0.0
        val delta = if (trade.side == Side.BUY) trade.quantity else -trade.quantity
        val next = current + delta
        if (next == 0.0) {
            positions.remove(trade.symbol)
        } else {
            positions[trade.symbol] = Position(trade.symbol, next)
        }
    }

    override fun positionFor(symbol: String): Position? = positions[symbol]
    override fun allPositions(): Map<String, Position> = positions.toMap()
}
```

### 6.3 `risk.RiskRule` and `risk.Decision`

```kotlin
package com.qkt.risk

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider

interface RiskRule {
    fun evaluate(order: Order, positions: PositionProvider): Decision
}

sealed class Decision {
    data object Approve : Decision()
    data class Reject(val reason: String) : Decision()
}
```

### 6.4 `risk.RiskEngine`

```kotlin
package com.qkt.risk

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider

class RiskEngine(
    private val rules: List<RiskRule>,
    private val positions: PositionProvider,
) {
    fun approve(order: Order): Decision {
        for (rule in rules) {
            val decision = rule.evaluate(order, positions)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }
}
```

### 6.5 `risk.rules.MaxPositionSize`

```kotlin
package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

class MaxPositionSize(
    private val symbol: String,
    private val maxQty: Double,
) : RiskRule {
    init { require(maxQty > 0.0) { "maxQty must be > 0: $maxQty" } }

    override fun evaluate(order: Order, positions: PositionProvider): Decision {
        if (order.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: 0.0
        val projected = if (order.side == Side.BUY) current + order.quantity else current - order.quantity
        return if (kotlin.math.abs(projected) <= maxQty) Decision.Approve
               else Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
    }
}
```

### 6.6 `risk.rules.MaxOpenPositions`

```kotlin
package com.qkt.risk.rules

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

class MaxOpenPositions(
    private val maxCount: Int,
) : RiskRule {
    init { require(maxCount > 0) { "maxCount must be > 0: $maxCount" } }

    override fun evaluate(order: Order, positions: PositionProvider): Decision {
        val openingNew = positions.positionFor(order.symbol) == null
        if (!openingNew) return Decision.Approve
        return if (positions.allPositions().size < maxCount) Decision.Approve
               else Decision.Reject("MaxOpenPositions: ${positions.allPositions().size} already open, max $maxCount")
    }
}
```

### 6.7 `events.Event` (extension)

```kotlin
data class RiskRejectedEvent(
    val order: Order,
    val reason: String,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()
```

Added alongside existing `TickEvent`, `CandleEvent`, `SignalEvent`, `OrderEvent`, `TradeEvent`.

### 6.8 `bus.EventBus` (extension)

```kotlin
private fun stamp(event: Event): Event {
    val ts = clock.now()
    val seq = sequencer.next()
    return when (event) {
        is TickEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is CandleEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is OrderEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is RiskRejectedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is TradeEvent -> event.copy(timestamp = ts, sequenceId = seq)
    }
}
```

Sealed-class exhaustiveness compile-enforces the new branch.

### 6.9 `app.Main` (extended)

The full file after Phase 3 changes:

```kotlin
fun main() {
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()
    val positions = PositionTracker()

    val bus = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    CandleAggregator(bus, TimeWindow.ONE_MINUTE)

    val strategies: List<Strategy> = listOf(
        EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0),
    )

    val rules: List<RiskRule> = listOf(
        MaxPositionSize(symbol = "XAUUSD", maxQty = 3.0),
    )
    val riskEngine = RiskEngine(rules, positions)

    strategies.forEach { strategy ->
        bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) } }
        bus.subscribe<CandleEvent> { e -> strategy.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) } }
    }

    bus.subscribe<SignalEvent> { e ->
        val order = e.signal.toOrder(ids.next(), clock.now())
        when (val decision = riskEngine.approve(order)) {
            is Decision.Approve -> bus.publish(OrderEvent(order))
            is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
        }
    }

    bus.subscribe<OrderEvent> { e -> broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) } }

    bus.subscribe<TradeEvent> { e -> positions.apply(e.trade) }    // FIRST trade subscriber

    bus.subscribe<TradeEvent> { e ->
        val t = e.trade
        val pos = positions.positionFor(t.symbol)?.quantity ?: 0.0
        log.info("FILLED: {} {} {} @ {} (position: {})", t.side, t.quantity, t.symbol, t.price, pos)
    }

    bus.subscribe<RiskRejectedEvent> { e ->
        val o = e.order
        log.info("REJECTED: {} {} {} ({})", o.side, o.quantity, o.symbol, e.reason)
    }

    bus.subscribe<CandleEvent> { e ->
        val c = e.candle
        log.info("CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
            c.symbol, c.open, c.high, c.low, c.close, c.volume, c.startTime, c.endTime)
    }

    val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock, tickIntervalMs = 1_000L)
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}
```

`Signal.toOrder` extension function unchanged from Phase 2b.

## 7. Build configuration changes

None. Phase 3 adds no new dependencies.

## 8. Error handling

| Condition | Behavior | Type |
|---|---|---|
| `MaxPositionSize(maxQty <= 0)` | `require(...)` throws on construction | Crash (bug) |
| `MaxOpenPositions(maxCount <= 0)` | `require(...)` throws on construction | Crash (bug) |
| `RiskEngine(rules = emptyList(), positions)` | Always returns `Decision.Approve` | Documented (degenerate engine) |
| `RiskRule.evaluate` throws | Exception propagates through SignalEvent subscriber → out of `bus.publish` → out of caller | Crash (bug) |
| `PositionTracker.apply(trade)` produces negative quantity | Position stored with negative value (short) | Documented (shorts allowed) |
| `PositionTracker.apply` brings quantity to exactly `0.0` | Entry removed from map | Documented |
| Floating-point drift causing near-zero quantity (e.g., `1e-15`) | Treated as non-zero, entry kept | Phase 3 limitation; fixed in Phase 3b BigDecimal migration |
| Risk rule that `bus.publish(...)` from inside `evaluate` | Re-entrant publication via depth-first dispatch | Documented but discouraged (rules SHOULD be pure) |
| Order with timestamp from before previous trade | Tracker accepts in arrival order | Documented (no time-based ordering enforcement) |

No try/catch in Phase 3 production code.

## 9. Testing strategy

### 9.1 Test files

| File | Class under test | Test count |
|---|---|---|
| `PositionTrackerTest.kt` | `PositionTracker` | ~6 |
| `RiskEngineTest.kt` | `RiskEngine` | ~5 |
| `MaxPositionSizeTest.kt` | `MaxPositionSize` | ~5 |
| `MaxOpenPositionsTest.kt` | `MaxOpenPositions` | ~5 |
| `EventBusTest.kt` (extension) | RiskRejectedEvent stamp | +1 |
| `EndToEndTest.kt` (extension) | full pipeline incl. risk path | +3 |

### 9.2 PositionTrackerTest cases

- `positionFor` returns null for unknown symbol.
- `apply` Buy creates position with positive quantity.
- `apply` Sell from zero creates short position with negative quantity.
- Consecutive Buys accumulate quantity.
- Sell that brings quantity to zero removes the position from the map.
- Tracks positions for multiple symbols independently.

### 9.3 MaxPositionSizeTest cases

- Approves order for non-target symbol.
- Approves order under the cap.
- Approves order at the cap (`abs(projected) == maxQty`).
- Rejects order over the cap on the long side.
- Rejects order over the cap on the short side.

### 9.4 MaxOpenPositionsTest cases

- Approves order when below the limit.
- Approves order for symbol already held even at the limit (not opening new).
- Rejects new opening when at the limit.
- Approves new opening when previous was closed (entry removed).
- Throws on non-positive `maxCount`.

### 9.5 RiskEngineTest cases

- Empty rules list always approves.
- Single approving rule returns `Approve`.
- Single rejecting rule returns `Reject` with that reason.
- Evaluates rules in order, first reject wins.
- All rules approving returns `Approve`.

### 9.6 EventBusTest extension

- `bus stamps RiskRejectedEvent on publish` — same shape as existing TickEvent/CandleEvent stamping tests.

### 9.7 EndToEndTest extension

- `risk approved order produces a fill and updates positions` — order under cap; assert `trades` non-empty, `positions.positionFor(...)?.quantity` non-zero.
- `risk rejected order publishes RiskRejectedEvent and skips broker` — order over cap; capture `RiskRejectedEvent`s, assert one captured, assert `trades` empty.
- `position tracker updated before subsequent FILLED log read` — verifies registration-order invariant; capture order in which subscribers fire; assert `positions.apply` ran before the FILLED logger reads positions.

The `wirePipeline` helper is extended to accept an optional `rules: List<RiskRule> = emptyList()` parameter. Existing 9 tests that don't pass rules get the empty list and behave exactly as before (no risk gating; risk approves everything).

### 9.8 Total test count

| Source | Phase 2b | Phase 3 delta | Phase 3 total |
|---|---|---|---|
| `MarketPriceTrackerTest` | 4 | 0 | 4 |
| `MockTickFeedTest` | 8 | 0 | 8 |
| `EveryNthTickBuyStrategyTest` | 5 | 0 | 5 |
| `MockBrokerTest` | 8 | 0 | 8 |
| `EngineTest` | 2 | 0 | 2 |
| `MonotonicSequenceGeneratorTest` | 3 | 0 | 3 |
| `EventBusTest` | 11 | +1 | 12 |
| `EndToEndTest` | 9 | +3 | 12 |
| `TimeWindowTest` | 5 | 0 | 5 |
| `CandleAggregatorTest` | 10 | 0 | 10 |
| `PositionTrackerTest` | 0 | +6 | 6 |
| `MaxPositionSizeTest` | 0 | +5 | 5 |
| `MaxOpenPositionsTest` | 0 | +5 | 5 |
| `RiskEngineTest` | 0 | +5 | 5 |
| **Total** | **65** | **+25** | **90** |

### 9.9 Conventions

- JUnit 5 + AssertJ. No mocks.
- Real types throughout. Anonymous objects for one-off `Strategy` impls.
- Capture lists for assertions on emitted events.
- `FixedClock`, fresh `MonotonicSequenceGenerator`, fresh `PositionTracker` per test class instance.
- Trade construction helper in test files to avoid `Trade(...)` boilerplate.
- ktlint enforced via `./gradlew check`.

## 10. Build & run

Commands unchanged from Phase 2b:

```
./gradlew test     # 90 tests
./gradlew run      # FILLED + REJECTED + (CANDLE) lines + Done.
./gradlew build    # compile + test + ktlintCheck + assemble
./scripts/precheck.sh
```

Expected `./gradlew run` output (sample shape):

```
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2382.18 (position: 1.0)
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2377.52 (position: 2.0)
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2392.73 (position: 3.0)
[main] INFO Main - REJECTED: BUY 1.0 XAUUSD (MaxPositionSize: |4.0| > 3.0 for XAUUSD)
[main] INFO Main - REJECTED: BUY 1.0 XAUUSD (MaxPositionSize: |4.0| > 3.0 for XAUUSD)
... (more REJECTED, total ~7) ...
[main] INFO Main - CANDLE: XAUUSD O=... [<startTs>, <endTs>)
... possibly more CANDLE lines ...
[main] INFO Main - Done.
```

10 signals emitted total. First 3 fill (positions 1.0, 2.0, 3.0). Subsequent 7 rejected (would push position to 4.0+). 1-3 CANDLE lines depending on clock alignment (Phase 2b behavior unchanged).

## 11. Out of scope (deferred)

| Feature | Phase |
|---|---|
| P&L (realized, unrealized, aggregate) | 3b |
| `Position.avgEntryPrice` | 3b |
| `BigDecimal` migration for prices/quantities | 3b |
| Lot-by-lot tracking (FIFO/LIFO) | Future |
| `BrokerRejectedEvent` (structured broker rejection) | Real-broker phase |
| Risk rules reading prices or clock | When a rule needs them |
| `MaxNotionalValue`, `MaxDailyLoss`, `SymbolWhitelist`, time-of-day | Add when needed |
| Custom user-defined runtime rules | Phase 5 DSL |
| Risk metrics, rejection counters | Phase 4+ |
| Position persistence | Future |
| Multi-account / portfolio | Future |
| Async / coroutines | post-Phase 5 |
| Backtest replay engine | Phase 4 |
| External DSL parser | Phase 5 |

## 12. Migration from Phase 2b

Phase 2b is on `main`. Phase 3 lands on a feature branch (`phase3-risk-positions` per the project skill), reviewed and merged.

Internal and additive: no existing test or behavior breaks. Phase 2b acceptance criteria still hold; new Phase 3 criteria added.

## 13. Acceptance criteria (Phase 3 done means)

- [ ] `Position`, `PositionProvider`, `PositionTracker` exist in `com.qkt.positions`.
- [ ] `RiskRule`, `Decision`, `RiskEngine` exist in `com.qkt.risk`.
- [ ] `MaxPositionSize`, `MaxOpenPositions` exist in `com.qkt.risk.rules`.
- [ ] `RiskRejectedEvent` exists in `com.qkt.events`; `EventBus.stamp` handles it.
- [ ] `Main.kt` wires `PositionTracker` (subscribed to TradeEvent FIRST), `RiskEngine` with `MaxPositionSize("XAUUSD", 3.0)`.
- [ ] Risk gate sits between SignalEvent and OrderEvent; rejected orders publish `RiskRejectedEvent`.
- [ ] All 90 tests pass.
- [ ] `./gradlew build` green.
- [ ] `./gradlew run` produces ~3 FILLED lines (each with `(position: N.N)`) and ~7 REJECTED lines, plus `Done.`.
- [ ] Phase 2b acceptance criteria still hold (CANDLE log lines may still appear; no Phase 2b regression).
- [ ] `EveryNthTickBuyStrategy` continues unchanged.
- [ ] `git log` clean history with conventional commit messages.
- [ ] No file exceeds the project skill's 200-line cap (with possible exceptions documented).

When all boxes are checked, Phase 3 is complete on the `phase3-risk-positions` branch, ready for merge to `main`.
