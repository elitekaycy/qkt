package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TimeRange
import com.qkt.engine.Engine
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DataStore
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.strategy.Strategy
import java.math.BigDecimal

class Backtest(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
) {
    constructor(
        strategies: List<Strategy>,
        rules: List<RiskRule> = emptyList(),
        ticks: List<Tick>,
        candleWindow: TimeWindow? = null,
        initialTimestamp: Long = 0L,
    ) : this(
        strategies = strategies,
        rules = rules,
        feed = HistoricalTickFeed(ticks),
        candleWindow = candleWindow,
        initialTimestamp = initialTimestamp,
    )

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

        feed.use { f ->
            while (true) {
                val tick = f.next() ?: break
                clock.time = tick.timestamp
                pipeline.ingest(tick)
            }
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

    companion object {
        fun fromStore(
            strategies: List<Strategy>,
            rules: List<RiskRule> = emptyList(),
            store: DataStore,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
        ): Backtest {
            val (from, to) = store.resolveRange(request)
            val resolved = MarketRequest(symbols = request.symbols, from = from, to = to)
            return fromSource(
                strategies = strategies,
                rules = rules,
                source = LocalMarketSource(store, FixedClock(time = to.toEpochMilli())),
                request = resolved,
                candleWindow = candleWindow,
            )
        }

        fun fromSource(
            strategies: List<Strategy>,
            rules: List<RiskRule> = emptyList(),
            source: MarketSource,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
        ): Backtest {
            require(MarketSourceCapability.TICKS in source.capabilities) {
                "Backtest requires a MarketSource that supports TICKS; ${source.name} has ${source.capabilities}"
            }
            val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
            val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
            val range = TimeRange(from, to)
            val perSymbolFeeds: List<TickFeed> =
                request.symbols.map { sym -> SequenceTickFeed(source.ticks(sym, range)) }
            val feed: TickFeed =
                if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
            return Backtest(
                strategies = strategies,
                rules = rules,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = from.toEpochMilli(),
            )
        }
    }
}
