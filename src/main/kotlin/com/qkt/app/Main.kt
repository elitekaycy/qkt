package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.strategy.Mode
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

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

    val strategies: List<Strategy> =
        listOf(
            EveryNthTickBuyStrategy("XAUUSD", n = 10, size = Money.of("1")),
        )
    val rules: List<RiskRule> =
        listOf(
            MaxPositionSize(symbol = "XAUUSD", maxQty = Money.of("3")),
        )
    val riskEngine = RiskEngine(rules, positions)

    val pipeline =
        TradingPipeline(
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
            sessionContext =
                SessionContext(
                    mode = Mode.BACKTEST,
                    clock = clock,
                    calendar = TradingCalendar.crypto(),
                    source = NullMarketSource,
                ),
            candleWindow = TimeWindow.ONE_MINUTE,
            onFilled = { trade, _ ->
                val pos = positions.positionFor(trade.symbol)?.quantity ?: Money.ZERO
                log.info(
                    "FILLED: {} {} {} @ {} (position: {}, realized: {}, unrealized: {})",
                    trade.side,
                    trade.quantity.stripTrailingZeros().toPlainString(),
                    trade.symbol,
                    trade.price.stripTrailingZeros().toPlainString(),
                    pos.stripTrailingZeros().toPlainString(),
                    pnl.realizedTotal().setScale(2, Money.ROUNDING),
                    pnl.unrealizedTotal().setScale(2, Money.ROUNDING),
                )
            },
            onRejected = { e ->
                val o = e.order
                log.info(
                    "REJECTED: {} {} {} ({})",
                    o.side,
                    o.quantity.stripTrailingZeros().toPlainString(),
                    o.symbol,
                    e.reason,
                )
            },
            onCandle = { c ->
                log.info(
                    "CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
                    c.symbol,
                    c.open.stripTrailingZeros().toPlainString(),
                    c.high.stripTrailingZeros().toPlainString(),
                    c.low.stripTrailingZeros().toPlainString(),
                    c.close.stripTrailingZeros().toPlainString(),
                    c.volume.stripTrailingZeros().toPlainString(),
                    c.startTime,
                    c.endTime,
                )
            },
        )

    val feed =
        MockTickFeed(
            symbol = "XAUUSD",
            startPrice = Money.of("2400"),
            count = 100,
            clock = clock,
            tickIntervalMs = 1_000L,
        )
    while (true) {
        val tick = feed.next() ?: break
        pipeline.ingest(tick)
    }
    log.info("Done.")
}
