package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import java.time.Instant

object IndicatorWarmerPerStreamFixtures {
    /** Records every (symbol, window, range) passed to `bars()` so tests can assert dispatch. */
    class RecordingMarketSource(
        private val seed: Map<Pair<String, TimeWindow>, List<Candle>>,
    ) : InMemoryMarketSource("Recording") {
        val barCalls: MutableList<Triple<String, TimeWindow, TimeRange>> = mutableListOf()

        init {
            for ((key, candles) in seed) {
                seedBars(key.first, key.second, candles)
            }
        }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            barCalls.add(Triple(symbol, window, range))
            return super.bars(symbol, window, range)
        }
    }

    val now = Instant.parse("2024-01-15T15:00:00Z")
    val candleStart = Instant.parse("2024-01-15T14:00:00Z").toEpochMilli()

    fun candle(
        symbol: String,
        startMs: Long,
        windowMs: Long,
    ): Candle =
        Candle(
            symbol = symbol,
            open = Money.of("100"),
            high = Money.of("110"),
            low = Money.of("90"),
            close = Money.of("105"),
            volume = Money.of("1"),
            startTime = startMs,
            endTime = startMs + windowMs,
        )

    fun pipeline(source: com.qkt.marketdata.source.MarketSource): TradingPipeline {
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
            strategies = emptyList(),
            riskEngine = riskEngine,
            riskState = riskState,
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }
}
