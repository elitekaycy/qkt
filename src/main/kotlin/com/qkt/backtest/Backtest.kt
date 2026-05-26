package com.qkt.backtest

import com.qkt.app.IndicatorWarmer
import com.qkt.app.TradingPipeline
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
import com.qkt.events.RiskRejectedEvent
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DataStore
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Instant

class Backtest(
    private val strategies: List<Pair<String, Strategy>>,
    private val rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    private val warmupSpec: WarmupSpec = WarmupSpec.None,
    private val symbols: List<String> = emptyList(),
    cadence: SampleCadence? = null,
    private val startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    private val instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
    private val brokerKind: BrokerKind = BrokerKind.PAPER,
) {
    private val cadence: SampleCadence =
        cadence
            ?: if (candleWindow != null) SampleCadence.CANDLE_CLOSE else SampleCadence.TICK

    init {
        require(this.cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
            "SampleCadence.CANDLE_CLOSE requires candleWindow"
        }
    }

    constructor(
        strategies: List<Pair<String, Strategy>>,
        rules: List<RiskRule> = emptyList(),
        ticks: List<Tick>,
        candleWindow: TimeWindow? = null,
        initialTimestamp: Long = 0L,
        cadence: SampleCadence? = null,
        startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
        instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
        brokerKind: BrokerKind = BrokerKind.PAPER,
    ) : this(
        strategies = strategies,
        rules = rules,
        feed = HistoricalTickFeed(ticks),
        candleWindow = candleWindow,
        initialTimestamp = initialTimestamp,
        cadence = cadence,
        startingBalance = startingBalance,
        instruments = instruments,
        brokerKind = brokerKind,
    )

    fun run(): BacktestResult {
        val clock = FixedClock(time = initialTimestamp)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker, instruments)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker, instruments)
        for ((id, _) in strategies) {
            strategyPnL.setStartingBalance(id, startingBalance)
        }
        val bus = EventBus(clock, sequencer)
        val engine = Engine(bus, priceTracker)
        val candleHub =
            com.qkt.dsl.compile
                .CandleHub()

        val dslStrategies =
            strategies.mapNotNull { (_, s) -> s as? com.qkt.dsl.compile.DslCompiledStrategy }
        val brokerSymbols: MutableMap<String, MutableSet<String>> = mutableMapOf()
        for (s in dslStrategies) {
            for (key in s.declaredStreams.values) {
                brokerSymbols
                    .getOrPut(key.broker) { mutableSetOf() }
                    .add(key.qktSymbol)
            }
        }
        val brokerFactory: () -> com.qkt.broker.Broker =
            when (brokerKind) {
                BrokerKind.PAPER -> { -> PaperBroker(bus, clock, priceTracker) }
                BrokerKind.MT5_SIM ->
                    { -> com.qkt.broker.MT5BrokerSimulator(bus, clock, priceTracker, instruments) }
            }
        val broker: com.qkt.broker.Broker =
            if (brokerSymbols.isEmpty()) {
                brokerFactory()
            } else {
                val routes =
                    brokerSymbols.map { (_, syms) ->
                        com.qkt.marketdata.source.SymbolPattern
                            .exactSet(syms.toSet()) to brokerFactory()
                    }
                com.qkt.broker.CompositeBroker(routes = routes, bus = bus)
            }
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        riskState.warmupComplete = true
        val riskEngine = RiskEngine(rules, emptyList(), positions, riskState)

        val tradeRecords = mutableListOf<TradeRecord>()
        val rejections = mutableListOf<RiskRejectedEvent>()
        val collector =
            EquityCurveCollector(
                cadence = cadence,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = strategies.map { it.first },
            )

        val pipelineHolder = arrayOfNulls<com.qkt.app.TradingPipeline>(1)
        val pipeline =
            TradingPipeline(
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
                calendar = calendar,
                source = source,
                candleWindow = candleWindow,
                candleHub = candleHub,
                onFilled = { trade, realized, strategyId ->
                    val risk = pipelineHolder[0]?.orderManager?.riskUsdFor(trade.orderId)
                    tradeRecords.add(TradeRecord(trade, realized, strategyId, risk))
                },
                onRejected = { e -> rejections.add(e) },
                onCandle = {},
                instruments = instruments,
            )
        pipelineHolder[0] = pipeline

        if (source !== NullMarketSource && warmupSpec !is WarmupSpec.None && symbols.isNotEmpty()) {
            IndicatorWarmer(source, pipeline).warmup(
                symbols = symbols,
                spec = warmupSpec,
                now = Instant.ofEpochMilli(initialTimestamp),
            )
        }

        feed.use { f ->
            while (true) {
                val tick = f.next() ?: break
                clock.time = tick.timestamp
                pipeline.ingest(tick)
            }
        }

        val annualizationFactor = annualizationFactorFor(collector.global())
        val globalReport =
            ReportBuilder.buildGlobal(
                trades = tradeRecords,
                equityCurve = collector.global(),
                finalRealized = pnl.realizedTotal(),
                finalUnrealized = pnl.unrealizedTotal(),
                annualizationFactor = annualizationFactor,
            )
        val perStrategy =
            strategies.associate { (id, _) ->
                id to
                    ReportBuilder.buildPerStrategy(
                        strategyId = id,
                        trades = tradeRecords.filter { it.strategyId == id },
                        equityCurve = collector.forStrategy(id),
                        finalRealized = strategyPnL.realizedFor(id),
                        finalUnrealized = strategyPnL.unrealizedTotalFor(id),
                        annualizationFactor = annualizationFactor,
                    )
            }

        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            finalPositions = positions.allPositions(),
            global = globalReport,
            perStrategy = perStrategy,
            cadence = cadence,
        )
    }

    private fun annualizationFactorFor(curve: List<EquitySample>): BigDecimal {
        if (cadence == SampleCadence.CANDLE_CLOSE && candleWindow != null) {
            return calendar.tradingPeriodsPerYear(candleWindow)
        }
        if (curve.size < 2) return BigDecimal("252")
        val spanMs = curve.last().timestamp - curve.first().timestamp
        if (spanMs <= 0L) return BigDecimal("252")
        val avgIntervalMs =
            BigDecimal(spanMs).divide(BigDecimal(curve.size - 1), Money.CONTEXT)
        val msPerYear = BigDecimal("31557600000")
        return msPerYear.divide(avgIntervalMs, Money.CONTEXT)
    }

    companion object {
        fun fromStore(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            store: DataStore,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            cadence: SampleCadence? = null,
            startingBalance: BigDecimal = BigDecimal.ZERO,
            instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
            /**
             * Phase 25A: optional pre-fetched bar store (populated by `qkt fetch`). When
             * present, `LocalMarketSource.bars()` reads from it instead of aggregating
             * from ticks for any day fully covered. Falls back to ticks for missing days.
             */
            barStore: com.qkt.marketdata.store.LocalBarStore? = null,
            brokerKind: BrokerKind = BrokerKind.PAPER,
        ): Backtest {
            val (from, to) = store.resolveRange(request)
            val resolved = MarketRequest(symbols = request.symbols, from = from, to = to)
            return fromSource(
                strategies = strategies,
                rules = rules,
                source = LocalMarketSource(store, FixedClock(time = to.toEpochMilli()), barStore = barStore),
                request = resolved,
                candleWindow = candleWindow,
                cadence = cadence,
                startingBalance = startingBalance,
                instruments = instruments,
                brokerKind = brokerKind,
            )
        }

        fun fromSource(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            source: MarketSource,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            warmupSpec: WarmupSpec = WarmupSpec.None,
            cadence: SampleCadence? = null,
            startingBalance: BigDecimal = BigDecimal.ZERO,
            instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
            brokerKind: BrokerKind = BrokerKind.PAPER,
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
                source = source,
                warmupSpec = warmupSpec,
                symbols = request.symbols,
                cadence = cadence,
                startingBalance = startingBalance,
                instruments = instruments,
                brokerKind = brokerKind,
            )
        }
    }
}
