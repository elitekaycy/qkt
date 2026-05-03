# qkt — Trading Engine Phase 4 Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 4 of the qkt trading platform. Adds a strategy-backtest harness that drives the engine from historical tick data deterministically, capturing trades, risk rejections, and mark-to-market P&L into a structured `BacktestResult`. Extracts a shared `TradingPipeline` so live (`Main.kt`) and backtest construct the same wiring; differ only in feed source and clock. Event-log replay is deferred to post-Phase-5.
**Phase 3b baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase3b-design.md`](2026-05-03-trading-engine-phase3b-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. Phase 1 shipped the core engine. Phase 2a/2b shipped the event bus, multi-strategy, candles. Phase 3/3b shipped risk + positions + P&L on a `BigDecimal` foundation. Phase 4 ships the strategy backtest harness — the feature that makes the runtime useful for "did this strategy work?" research.

Backtest design principle: **the backtest pipeline is structurally identical to live trading**. Same engine, same strategies, same risk rules, same broker (mock for backtest, real for live in a future phase), same position tracker, same P&L calculator. Only two things differ: the `TickFeed` impl (historical instead of websocket) and the `Clock` impl (advances per tick instead of wall-clock).

This document is **Phase 4 only**.

## 2. Phase 4 — what we are building

A strategy backtest harness. Concrete deliverables:

1. **`TradingPipeline`** — new class in `com.qkt.app` that owns all subscriber wiring previously in `Main.kt`. Constructor takes all components + observation callbacks; `init` registers every `bus.subscribe<...>`; public `ingest(tick: Tick)` calls `engine.onTick(tick)`. Replaces inline wiring in `Main.kt`.

2. **`HistoricalTickFeed`** — new class in `com.qkt.marketdata` implementing `TickFeed` over a `List<Tick>`. Walks the list once, returns null on exhaustion.

3. **`Backtest`** — new class in `com.qkt.app`. Constructor takes `(strategies, rules, ticks, candleWindow?, initialTimestamp = 0L)`. Single `run(): BacktestResult` method. Constructs all components, builds a `TradingPipeline` with capture callbacks, registers a mark-to-market drawdown subscriber LAST, runs the loop `for (tick in ticks) { clock.time = tick.timestamp; pipeline.ingest(tick) }`, computes metrics, returns the result.

4. **`BacktestResult`** — new data class. Carries `trades: List<TradeRecord>`, `rejections`, `finalPositions`, `realizedTotal`, `unrealizedTotal`, `totalPnL`, `tradeCount`, `winRate`, `maxDrawdown`.

5. **`TradeRecord(trade, realized: BigDecimal)`** — pairs each fill with its realized P&L for the win-rate computation.

6. **`Main.kt` restructured** — uses `TradingPipeline` instead of inline wiring. Same observable behavior (3 FILLED + 7 REJECTED + Done. with the same formatting).

End condition: 107 → 116 tests, all green. New backtest tests run in milliseconds (no wall-clock waiting). `./gradlew run` produces identical Phase 3b output. Phase 3b acceptance criteria still hold.

**Phase 4 does NOT include** (deferred):

- Event-log recording for replay/audit (post-Phase-5)
- CSV/JSON tick reader (future)
- Sharpe ratio, Sortino, Calmar, equity curve, advanced analytics (future analytics phase)
- Multi-asset benchmark comparison (future)
- Parameter sweep harness (caller does it via a `for` loop calling `Backtest(...).run()`)
- Walk-forward optimization (future)
- Slippage / commission modeling (real-broker phase)
- Position persistence across runs (future)
- Async / coroutines (post-Phase 5)
- Streaming feed for datasets exceeding memory (future)

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Phase 4 scope = strategy backtest only.** Event-log replay deferred to post-Phase-5. | Strategy backtest is the user-visible product (every quant framework's headline feature). Event-log replay is a debugging tool that doesn't unlock new user value. |
| D2 | **Backtest mirrors live structurally.** Shared `TradingPipeline` abstraction. Live and backtest differ only in feed source and clock. | Same engine, same strategies, same risk, same broker. Eliminates "live vs backtest divergence" (a common source of bugs in quant systems). DSL endgame ships strategies that run identically in both modes. |
| D3 | **`HistoricalTickFeed(List<Tick>)`** is the Phase 4 feed impl. CSV/JSON readers are out of scope. | The `TickFeed` interface already exists. List-based impl is ~10 lines. Users with custom data formats implement their own `TickFeed`. CSV format design is a rabbit hole (schemas vary widely); defer until a canonical use case forces it. |
| D4 | **Backtest clock: `FixedClock` advanced to `tick.timestamp` before each `ingest`.** No new clock type. | Mirrors live (where `clock.now() ≈ tick.timestamp`). Order/event timestamps stay realistic. Backtest determinism free. Time compression natural (CPU speed, not wall clock). |
| D5 | **`BacktestResult` includes basic metrics.** `tradeCount`, `winRate`, `maxDrawdown` computed once at end of run. | Users immediately want "did this work?" These four numbers answer it. Computing them is cheap. Sharpe/equity-curve are separable analytics for a future phase. |
| D6 | **Drawdown is mark-to-market.** Snapshot `pnl.totalPnL()` after each tick, track running peak and max drawdown. | Industry standard. Realized-only drawdown hides risk on positions held underwater. Mark-to-market reflects the lived experience of running a strategy. |
| D7 | **`Backtest` API: single-call.** `Backtest(strategies, rules, ticks, candleWindow?, initialTimestamp).run(): BacktestResult`. | Simplest UX. Constructor with default parameters handles the few knobs. Builders are premature for ~3 knobs. Caller controls parameter sweeps via outer loops. |
| D8 | **`TradingPipeline`** is a class with constructor wiring + `ingest(tick)`. Callbacks: `onFilled: (Trade, BigDecimal) -> Unit`, `onRejected: (RiskRejectedEvent) -> Unit`, `onCandle: (Candle) -> Unit`. Subscribes everything in `init`. | Constructor side-effects match `CandleAggregator` pattern (Phase 2b). Dependency injection via constructor params. `ingest` is the single mutating contract. Callbacks let live (SLF4J) and backtest (capture lists) observe identically. |
| D9 | **`onFilled` signature is `(Trade, BigDecimal)`** — realized P&L flows to observers alongside the trade. | The realized number is computed by `positions.apply(trade)` and existed only in the same handler. Forwarding to the callback avoids re-derivation by observers. Backtest uses it directly to build `TradeRecord`. |
| D10 | **Mark-to-market drawdown subscriber registered LAST among `TickEvent` subscribers.** | Depth-first dispatch + registration order: aggregator (if any) and strategies fire first; their downstream signal/order/trade chains complete; `pnl.totalPnL()` is post-tick when the drawdown subscriber reads it. |
| D11 | **`tradeCount` counts every fill (each open and each close).** | Round-trip counting requires lot pairing (deferred per Phase 3b D4). Per-fill count matches the `trades: List<TradeRecord>` size — simple and consistent. |
| D12 | **Win rate definition: closing-trade wins / closing-trade total.** Trades with zero realized (opens, adds) excluded from numerator and denominator. Empty closing list → win rate = `Money.ZERO`. | Aligns with average-cost accounting. Industry-standard reporting. Excludes opens cleanly. |
| D13 | **`Main.kt` is restructured to use `TradingPipeline`.** Behavior unchanged; wiring extracted. | Single source of pipeline wiring for live and backtest. Avoids two-place edits when the wiring evolves. |
| D14 | **`EndToEndTest.kt` is unchanged in Phase 4.** Continues its manual wiring style. | Out of scope; refactoring the test file is a separate cleanup. The two styles coexist. |

## 4. Architecture

### 4.1 Pipeline (Phase 4)

```
                  ┌──────────────────────────────────────────┐
                  │             TradingPipeline              │
                  │   - bus subscriptions for all events     │
                  │   - signal→order + risk gate             │
                  │   - broker handler                       │
                  │   - positions.apply + pnl.recordRealized │
                  │   - candle aggregator (optional)         │
                  │   - observation callbacks                │
                  └──────────────────────────────────────────┘
                                     ▲
                                     │ ingest(tick)
                       ┌─────────────┴─────────────┐
                       │                           │
         ┌─────────────────────────┐   ┌─────────────────────────┐
         │   live: Main.kt         │   │   Backtest.run()        │
         │   - MockTickFeed        │   │   - HistoricalTickFeed  │
         │   - SystemClock         │   │   - FixedClock          │
         │   - SLF4J callbacks     │   │   - capture callbacks   │
         │   - while loop          │   │   - drawdown subscriber │
         └─────────────────────────┘   │     (registered LAST)   │
                                       │   - returns Result      │
                                       └─────────────────────────┘
```

### 4.2 One backtest tick

```
1. clock.time = tick.timestamp
2. pipeline.ingest(tick)  →  engine.onTick(tick)
   2a. priceTracker.update(tick.symbol, tick.price)
   2b. bus.publish(TickEvent(tick))
       3a. CandleAggregator (if windowed) handler
            may publish CandleEvent → strategy.onCandle → may emit SignalEvent → ...
       3b. each strategy.onTick → may emit SignalEvent
            → SignalEvent subscriber: build order, risk-approve or reject
              if Approve: publish OrderEvent → broker.execute → TradeEvent
                → positions.apply + pnl.recordRealized + onFilled(trade, realized)
                  → tradeRecords.add(TradeRecord(trade, realized))
              if Reject: publish RiskRejectedEvent → onRejected(e)
                → rejections.add(e)
       3c. drawdown subscriber (LAST):
            equity = pnl.totalPnL()
            peakEquity = max(peakEquity, equity)
            maxDrawdown = max(maxDrawdown, peakEquity - equity)
3. tick processing complete; loop continues
```

### 4.3 Result assembly (after the loop)

```
return BacktestResult(
    trades = tradeRecords.toList(),
    rejections = rejections.toList(),
    finalPositions = positions.allPositions(),
    realizedTotal = pnl.realizedTotal(),
    unrealizedTotal = pnl.unrealizedTotal(),
    totalPnL = pnl.totalPnL(),
    tradeCount = tradeRecords.size,
    winRate = computeWinRate(tradeRecords),
    maxDrawdown = maxDrawdown,
)
```

### 4.4 Component dependencies (Phase 4 delta)

```
app/TradingPipeline.kt    ──▶ bus, candles, common, engine, events, execution, marketdata, pnl, positions, risk, strategy
app/Backtest.kt           ──▶ everything TradingPipeline imports + marketdata/HistoricalTickFeed
app/BacktestResult.kt     ──▶ execution, events, positions, common
app/TradeRecord.kt        ──▶ execution
marketdata/HistoricalTickFeed.kt ──▶ marketdata (TickFeed, Tick)
app/Main.kt               ──▶ broker, bus, candles, common, engine, events, execution, marketdata, pnl, positions, risk, strategy + app/TradingPipeline
```

No new top-level packages. No cycles.

### 4.5 What changes from Phase 3b

- `app/Main.kt` — restructured to construct a `TradingPipeline` and pass observation callbacks. Behavior unchanged.
- New: `app/TradingPipeline.kt`, `app/Backtest.kt`, `app/BacktestResult.kt`, `app/TradeRecord.kt`.
- New: `marketdata/HistoricalTickFeed.kt`.
- New tests: `marketdata/HistoricalTickFeedTest.kt`, `app/BacktestTest.kt`.

### 4.6 What does NOT change

- `Engine`, `EventBus`, all event types — unchanged.
- `Strategy`, `RiskRule`, `RiskEngine`, `MaxPositionSize`, `MaxOpenPositions` — unchanged.
- `MarketPriceProvider`, `PositionTracker`, `PnLCalculator`, `MockBroker`, `Money`, `Clock`/`FixedClock`, `IdGenerator`, `SequenceGenerator` — unchanged.
- `MockTickFeed`, `EveryNthTickBuyStrategy`, `OrderFactory.toOrder` — unchanged.
- `CandleAggregator`, `TimeWindow` — unchanged.
- `EndToEndTest.kt` — unchanged (continues manual wiring).
- All Phase 1+2a+2b+3+3b tests — pass unchanged.

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/
│   ├── Backtest.kt                          # NEW
│   ├── BacktestResult.kt                    # NEW
│   ├── Main.kt                              # restructured
│   ├── TradeRecord.kt                       # NEW
│   └── TradingPipeline.kt                   # NEW
├── broker/                                  # unchanged
├── bus/                                     # unchanged
├── candles/                                 # unchanged
├── common/                                  # unchanged
├── engine/                                  # unchanged
├── events/                                  # unchanged
├── execution/                               # unchanged
├── marketdata/
│   ├── Candle.kt                            # unchanged
│   ├── HistoricalTickFeed.kt                # NEW
│   ├── MarketPriceProvider.kt               # unchanged
│   ├── MockTickFeed.kt                      # unchanged
│   ├── Tick.kt                              # unchanged
│   └── TickFeed.kt                          # unchanged
├── pnl/                                     # unchanged
├── positions/                               # unchanged
├── risk/                                    # unchanged
└── strategy/                                # unchanged

src/test/kotlin/com/qkt/
├── app/
│   ├── BacktestTest.kt                      # NEW (~6 tests)
│   └── EndToEndTest.kt                      # unchanged
├── broker/MockBrokerTest.kt                 # unchanged
├── bus/EventBusTest.kt                      # unchanged
├── candles/                                 # unchanged
├── common/
│   ├── MoneyTest.kt                         # unchanged
│   └── MonotonicSequenceGeneratorTest.kt    # unchanged
├── engine/EngineTest.kt                     # unchanged
├── marketdata/
│   ├── HistoricalTickFeedTest.kt            # NEW (~3 tests)
│   ├── MarketPriceTrackerTest.kt            # unchanged
│   └── MockTickFeedTest.kt                  # unchanged
├── pnl/PnLCalculatorTest.kt                 # unchanged
├── positions/PositionTrackerTest.kt         # unchanged
├── risk/                                    # unchanged
└── strategy/EveryNthTickBuyStrategyTest.kt  # unchanged
```

Five new production files + two new test files.

## 6. Type & interface signatures

### 6.1 `marketdata.HistoricalTickFeed`

```kotlin
package com.qkt.marketdata

class HistoricalTickFeed(
    private val ticks: List<Tick>,
) : TickFeed {
    private var index = 0

    override fun next(): Tick? = if (index < ticks.size) ticks[index++] else null
}
```

### 6.2 `app.TradingPipeline`

```kotlin
package com.qkt.app

class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionTracker,
    val pnl: PnLCalculator,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Strategy>,
    val riskEngine: RiskEngine,
    val candleWindow: TimeWindow? = null,
    val onFilled: (Trade, BigDecimal) -> Unit = { _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
) {
    init {
        if (candleWindow != null) CandleAggregator(bus, candleWindow)

        strategies.forEach { strategy ->
            bus.subscribe<TickEvent> { e ->
                strategy.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
            }
            bus.subscribe<CandleEvent> { e ->
                strategy.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val order = e.signal.toOrder(ids.next(), clock.now())
            when (val decision = riskEngine.approve(order)) {
                is Decision.Approve -> bus.publish(OrderEvent(order))
                is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
            }
        }
        bus.subscribe<OrderEvent> { e ->
            broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
        }
        bus.subscribe<TradeEvent> { e ->
            val realized = positions.apply(e.trade)
            pnl.recordRealized(realized)
            onFilled(e.trade, realized)
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        engine.onTick(tick)
    }
}
```

### 6.3 `app.TradeRecord`

```kotlin
package com.qkt.app

import com.qkt.execution.Trade
import java.math.BigDecimal

data class TradeRecord(
    val trade: Trade,
    val realized: BigDecimal,
)
```

### 6.4 `app.BacktestResult`

```kotlin
package com.qkt.app

import com.qkt.events.RiskRejectedEvent
import com.qkt.positions.Position
import java.math.BigDecimal

data class BacktestResult(
    val trades: List<TradeRecord>,
    val rejections: List<RiskRejectedEvent>,
    val finalPositions: Map<String, Position>,
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    val winRate: BigDecimal,
    val maxDrawdown: BigDecimal,
)
```

### 6.5 `app.Backtest`

```kotlin
package com.qkt.app

class Backtest(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule> = emptyList(),
    private val ticks: List<Tick>,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
) {
    fun run(): BacktestResult {
        val clock = FixedClock(time = initialTimestamp)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules, positions)

        val tradeRecords = mutableListOf<TradeRecord>()
        val rejections = mutableListOf<RiskRejectedEvent>()
        var peakEquity: BigDecimal = Money.ZERO
        var maxDrawdown: BigDecimal = Money.ZERO

        val pipeline = TradingPipeline(
            clock = clock,
            ids = ids,
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            bus = bus,
            broker = broker,
            engine = engine,
            strategies = strategies,
            riskEngine = riskEngine,
            candleWindow = candleWindow,
            onFilled = { trade, realized -> tradeRecords.add(TradeRecord(trade, realized)) },
            onRejected = { e -> rejections.add(e) },
            onCandle = {},
        )

        bus.subscribe<TickEvent> {
            val equity = pnl.totalPnL()
            if (equity > peakEquity) peakEquity = equity
            val drawdown = peakEquity.subtract(equity)
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        val feed = HistoricalTickFeed(ticks)
        while (true) {
            val tick = feed.next() ?: break
            clock.time = tick.timestamp
            pipeline.ingest(tick)
        }

        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            finalPositions = positions.allPositions(),
            realizedTotal = pnl.realizedTotal(),
            unrealizedTotal = pnl.unrealizedTotal(),
            totalPnL = pnl.totalPnL(),
            tradeCount = tradeRecords.size,
            winRate = computeWinRate(tradeRecords),
            maxDrawdown = maxDrawdown,
        )
    }

    private fun computeWinRate(records: List<TradeRecord>): BigDecimal {
        val closing = records.filter { it.realized.signum() != 0 }
        if (closing.isEmpty()) return Money.ZERO
        val wins = closing.count { it.realized.signum() > 0 }
        return BigDecimal(wins)
            .divide(BigDecimal(closing.size), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

### 6.6 `app.Main` — restructured

`Main.kt` constructs all components (clock, ids, sequencer, trackers, bus, broker, engine, pnl, riskEngine), then builds a `TradingPipeline` with observation callbacks that log via SLF4J. The pipeline owns subscriber registration. The main loop is unchanged in shape: pull a tick from `MockTickFeed`, call `pipeline.ingest(tick)`.

Sketch:

```kotlin
fun main() {
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()
    val positions = PositionTracker()
    val pnl = PnLCalculator(positions, priceTracker)
    val bus = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    val pipeline = TradingPipeline(
        clock, ids, sequencer, priceTracker, positions, pnl, bus, broker, engine,
        strategies = listOf(EveryNthTickBuyStrategy("XAUUSD", n = 10, size = Money.of("1"))),
        riskEngine = RiskEngine(listOf(MaxPositionSize("XAUUSD", Money.of("3"))), positions),
        candleWindow = TimeWindow.ONE_MINUTE,
        onFilled = { trade, _ ->
            val pos = positions.positionFor(trade.symbol)?.quantity ?: Money.ZERO
            log.info("FILLED: {} {} {} @ {} (position: {}, realized: {}, unrealized: {})",
                trade.side,
                trade.quantity.stripTrailingZeros().toPlainString(),
                trade.symbol,
                trade.price.stripTrailingZeros().toPlainString(),
                pos.stripTrailingZeros().toPlainString(),
                pnl.realizedTotal().setScale(2, Money.ROUNDING),
                pnl.unrealizedTotal().setScale(2, Money.ROUNDING))
        },
        onRejected = { e -> log.info("REJECTED: {} {} {} ({})", e.order.side,
            e.order.quantity.stripTrailingZeros().toPlainString(), e.order.symbol, e.reason) },
        onCandle = { c -> log.info("CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
            c.symbol,
            c.open.stripTrailingZeros().toPlainString(),
            c.high.stripTrailingZeros().toPlainString(),
            c.low.stripTrailingZeros().toPlainString(),
            c.close.stripTrailingZeros().toPlainString(),
            c.volume.stripTrailingZeros().toPlainString(),
            c.startTime, c.endTime) },
    )

    val feed = MockTickFeed("XAUUSD", Money.of("2400"), 100, clock, tickIntervalMs = 1_000L)
    while (true) {
        val tick = feed.next() ?: break
        pipeline.ingest(tick)
    }
    log.info("Done.")
}
```

Same observable behavior as Phase 3b. Implementation plan provides the exact source.

## 7. Build configuration changes

None. Phase 4 introduces no new dependencies.

## 8. Error handling

| Condition | Behavior | Type |
|---|---|---|
| `Backtest.run()` with empty ticks | proceeds; main loop terminates immediately; result has empty trades/rejections, zero P&L, zero drawdown | Documented |
| `Backtest` with empty strategies | proceeds; no signals emitted; result has empty trades; clock advances per tick | Documented |
| Out-of-order ticks (regressing timestamps) | accepted in supplied order; clock walks accordingly | Documented (caller's responsibility to sort) |
| Subscriber exception during `Backtest.run()` | propagates out of the loop; capture state up to that point is lost | Crash (bug) |
| `Tick` with NaN price | not applicable — BigDecimal can't be NaN | n/a |
| `Tick` with negative price | accepted; downstream doesn't validate | Documented |
| Multiple `Backtest.run()` calls | per-call fresh state; identical inputs → identical results | Documented |
| `Backtest` with all-loser closing trades | `winRate = 0`; `maxDrawdown` reflects equity drops | Tested |
| `Backtest` with no closing trades (all opens) | `winRate = Money.ZERO` (empty-closing-list branch); `tradeCount` = number of opens | Documented |

No try/catch in production code. Phase 1's fail-fast policy continues.

## 9. Testing strategy

### 9.1 Test files

| File | Phase 3b → Phase 4 |
|---|---|
| `marketdata/HistoricalTickFeedTest.kt` | NEW (~3 tests) |
| `app/BacktestTest.kt` | NEW (~6 tests) |
| `app/EndToEndTest.kt` | unchanged (still does manual wiring) |
| All other Phase 3b tests | unchanged |

### 9.2 HistoricalTickFeedTest cases

- `next` returns ticks in order then `null`.
- empty list returns `null` on first call.
- repeated `next` calls past end keep returning `null`.

### 9.3 BacktestTest cases

- `empty ticks produces empty result with zero metrics` — Backtest with `ticks = emptyList()`. Result: empty `trades`, empty `rejections`, all P&L `Money.ZERO`, `tradeCount = 0`.
- `single buy produces one trade and zero realized` — strategy buys once. Expected: 1 `TradeRecord` with `realized.signum() == 0`, `tradeCount = 1`, `winRate = Money.ZERO`.
- `buy then sell produces realized PnL and increments win rate` — strategy buys at $100, sells at $110. Expected: 2 trades; second realized = $10; `winRate = Money.of("1")` (1/1 closing trades won).
- `risk-rejected order appears in rejections, not trades` — rule rejects all (e.g., `MaxPositionSize` with cap 0.5 against size 1.0). Expected: empty trades, 1 rejection, `tradeCount = 0`.
- `mark-to-market drawdown captures unrealized swings on open positions` — ticks at 100, 90, 110; strategy buys on first. Expected `maxDrawdown == Money.of("10")` (peak equity 0, trough -10).
- `max position size rule rejects subsequent buys after limit reached` — strategy emits 5 buys; rule caps at 2. Expected: 2 trades, 3 rejections, `finalPositions["XAUUSD"]?.quantity == Money.of("2")`.

### 9.4 Total test count

| Source | Phase 3b | Phase 4 delta | Phase 4 total |
|---|---|---|---|
| (Phase 3b unchanged) | 107 | 0 | 107 |
| `HistoricalTickFeedTest` | 0 | +3 | 3 |
| `BacktestTest` | 0 | +6 | 6 |
| **Total** | **107** | **+9** | **116** |

### 9.5 Conventions

- AssertJ `isEqualByComparingTo(Money.of("..."))` for BigDecimal.
- Real types throughout. Anonymous `object : Strategy { ... }` for one-off test strategies.
- Capture lists where needed (Backtest already captures via callbacks; tests use those).
- `tick(symbol, price, ts)` helper to reduce boilerplate.
- ktlint enforced.

## 10. Build & run

Commands unchanged. `./gradlew run` produces same output as Phase 3b — 3 FILLED + 7 REJECTED + (1-3 CANDLE) + Done. Internal wiring uses `TradingPipeline`; no observable difference.

## 11. Out of scope (deferred)

| Feature | Phase |
|---|---|
| Event-log recording for replay/audit | Post-Phase-5 (debugging tool) |
| CSV/JSON tick reader | Future |
| Sharpe ratio, Sortino, Calmar, equity curve | Future analytics phase |
| Multi-asset benchmark comparison | Future analytics |
| Parameter sweep harness | Caller does this in a `for` loop |
| Walk-forward optimization | Future |
| Monte Carlo simulation | Future |
| Slippage / commission modeling in MockBroker | Real-broker phase |
| Multi-currency support | Future |
| Position persistence | Future |
| Async / coroutines | post-Phase 5 |
| Streaming feed (datasets exceeding memory) | Future |
| Distinct live-trading entry point | Real-broker phase |
| `BacktestResult.candles` (capture candles) | Future if needed |

## 12. Migration from Phase 3b

Phase 3b is on `main`. Phase 4 lands on a feature branch (`phase4-backtest`), reviewed and merged.

Internal: `Main.kt`'s wiring extracts to `TradingPipeline`. Existing tests (`EndToEndTest.kt`, `EventBusTest.kt`, etc.) continue to use their own wiring; they're unaffected. The `./gradlew run` output is byte-for-byte identical to Phase 3b's.

## 13. Acceptance criteria (Phase 4 done means)

- [ ] `TradingPipeline` exists in `com.qkt.app` with constructor wiring + `ingest(tick)`.
- [ ] `HistoricalTickFeed` exists in `com.qkt.marketdata` implementing `TickFeed` over `List<Tick>`.
- [ ] `Backtest` exists in `com.qkt.app` with `(strategies, rules, ticks, candleWindow?, initialTimestamp).run(): BacktestResult` API.
- [ ] `BacktestResult` and `TradeRecord` exist in `com.qkt.app`.
- [ ] `Main.kt` is restructured to use `TradingPipeline`.
- [ ] All 116 tests pass.
- [ ] `./gradlew build` green.
- [ ] `./gradlew run` produces same output as Phase 3b (3 FILLED + 7 REJECTED + Done.).
- [ ] Determinism: running the same `Backtest(...)` twice produces an identical `BacktestResult`.
- [ ] Mark-to-market drawdown captures unrealized swings (verified by `BacktestTest`).
- [ ] Phase 3b acceptance criteria still hold.
- [ ] `git log` clean history with conventional commit messages.

When all checked, Phase 4 is done. Strategies can be backtested via `Backtest(...).run()` returning a `BacktestResult` with realized + unrealized P&L, drawdown, trade count, win rate. The DSL endgame (Phase 5) compiles user-written strategies into the same `Strategy` type and runs them through this same backtest harness.
