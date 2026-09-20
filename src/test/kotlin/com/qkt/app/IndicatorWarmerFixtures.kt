package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import java.time.Instant

object IndicatorWarmerFixtures {
    val now: Instant = Instant.parse("2024-01-15T15:00:00Z")

    fun newPipeline(strategies: List<Pair<String, Strategy>>): TradingPipeline {
        val clock = FixedClock(time = now.toEpochMilli())
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val riskState = com.qkt.risk.RiskState(pnl, strategyPnL, clock, bus)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        return TradingPipeline(
            clock = clock,
            ids = ids,
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = broker,
            engine = engine,
            strategies = strategies,
            riskEngine = riskEngine,
            riskState = riskState,
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    fun candle(
        close: String,
        startMs: Long,
    ): Candle =
        Candle(
            "X",
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of("1"),
            startMs,
            startMs + 60_000L,
        )
}
