package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

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

    val strategies: List<Strategy> =
        listOf(
            EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0),
        )

    val rules: List<RiskRule> =
        listOf(
            MaxPositionSize(symbol = "XAUUSD", maxQty = 3.0),
        )
    val riskEngine = RiskEngine(rules, positions)

    strategies.forEach { strategy ->
        bus.subscribe<TickEvent> { e ->
            strategy.onTick(e.tick) { signal -> bus.publish(SignalEvent(signal)) }
        }
        bus.subscribe<CandleEvent> { e ->
            strategy.onCandle(e.candle) { signal -> bus.publish(SignalEvent(signal)) }
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

    bus.subscribe<TradeEvent> { e -> positions.apply(e.trade) }

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
        log.info(
            "CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
            c.symbol,
            c.open,
            c.high,
            c.low,
            c.close,
            c.volume,
            c.startTime,
            c.endTime,
        )
    }

    val feed =
        MockTickFeed(
            symbol = "XAUUSD",
            startPrice = 2400.0,
            count = 100,
            clock = clock,
            tickIntervalMs = 1_000L,
        )
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}

private fun Signal.toOrder(
    id: String,
    ts: Long,
): Order =
    when (this) {
        is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
