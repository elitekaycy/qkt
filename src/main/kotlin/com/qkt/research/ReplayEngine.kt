package com.qkt.research

import com.qkt.app.IndicatorWarmer
import com.qkt.app.TradingPipeline
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.EquityCurveCollector
import com.qkt.backtest.EquityMetrics
import com.qkt.backtest.ReportBuilder
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.RiskRejectedEvent
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.CommissionBook
import com.qkt.pnl.PerLotCommission
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.Position
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

/**
 * The shared replay core: builds the full trading pipeline once and advances ticks
 * through it on demand. Both the batch [com.qkt.backtest.Backtest] and the interactive
 * research session drive this one type, so their results cannot diverge — pacing only
 * decides when we stop pulling ticks, never the tick->ingest order.
 *
 * Construction mirrors `Backtest.run()` exactly (same wiring, same order) so a full
 * [runToEnd] is bit-identical to the previous batch path.
 */
class ReplayEngine(
    private val strategies: List<Pair<String, Strategy>>,
    rules: List<RiskRule> = emptyList(),
    haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    warmupSpec: WarmupSpec = WarmupSpec.None,
    symbols: List<String> = emptyList(),
    cadence: SampleCadence? = null,
    private val startingBalance: BigDecimal = BigDecimal.ZERO,
    instruments: InstrumentRegistry = NoopInstrumentRegistry,
    brokerKind: BrokerKind = BrokerKind.PAPER,
    private val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
) : AutoCloseable {
    private val cadence: SampleCadence =
        cadence ?: if (candleWindow != null) SampleCadence.CANDLE_CLOSE else SampleCadence.TICK

    /** Timestamp of the last ingested tick (or [initialTimestamp] before the first). */
    var currentTimestamp: Long = initialTimestamp
        private set

    /** Count of ticks ingested so far. */
    var ticksIngested: Long = 0L
        private set

    /** Count of candle closes seen so far (primary `candleWindow`). */
    var barsClosed: Long = 0L
        private set

    /** True once the feed has been fully drained. */
    var exhausted: Boolean = false
        private set

    private val clock = FixedClock(time = initialTimestamp)
    private val priceTracker = MarketPriceTracker()
    private val positions = PositionTracker()
    private val pnl: PnLCalculator
    private val strategyPnL: StrategyPnL
    private val collector: EquityCurveCollector
    private val commissionBook = CommissionBook(PerLotCommission(instruments))
    private val pipeline: TradingPipeline
    private val tradeRecords = mutableListOf<TradeRecord>()
    private val rejections = mutableListOf<RiskRejectedEvent>()
    private val halts = mutableListOf<com.qkt.events.RiskEvent.Halted>()
    private val tape = mutableListOf<TapeEvent>()

    init {
        require(this.cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
            "SampleCadence.CANDLE_CLOSE requires candleWindow"
        }
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        pnl = PnLCalculator(positions, priceTracker, instruments)
        val strategyPositions = StrategyPositionTracker()
        strategyPnL = StrategyPnL(strategyPositions, priceTracker, instruments)
        for ((id, _) in strategies) strategyPnL.setStartingBalance(id, startingBalance)
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
                brokerSymbols.getOrPut(key.broker) { mutableSetOf() }.add(key.qktSymbol)
            }
        }
        com.qkt.instrument.QuoteCurrencyGuard
            .assertAccountQuoted(symbols + brokerSymbols.values.flatten())
        // Same deploy-time contract as live: a real registry that cannot resolve a traded
        // symbol fails the run up front instead of silently booking contractSize=1.
        if (instruments !is NoopInstrumentRegistry) {
            for (symbol in (symbols + brokerSymbols.values.flatten()).distinct()) {
                if (!com.qkt.instrument.QuoteCurrencyGuard
                        .requiresContractSizeMeta(symbol)
                ) {
                    continue
                }
                requireNotNull(instruments.lookup(symbol)) {
                    "InstrumentMeta unresolvable for $symbol — refusing to backtest " +
                        "(PnL would silently book contractSize=1)"
                }
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
                com.qkt.broker.CompositeBroker(
                    routes =
                        brokerSymbols.map { (_, syms) ->
                            com.qkt.marketdata.source.SymbolPattern
                                .exactSet(syms.toSet()) to brokerFactory()
                        },
                    bus = bus,
                )
            }
        // Mirror the live RiskState construction (balance basis + halt rules) so a
        // strategy that would halt live halts at the same point in its backtest.
        val riskState = RiskState(pnl, strategyPnL, clock, bus, startingBalance)
        riskState.warmupComplete = true
        // The same always-on pre-trade controls live runs (#393): a backtest must show
        // the rejection a live deploy would produce, not sail past it.
        val preTradeRules =
            com.qkt.risk.rules.PreTradeControls.standard(
                prices = priceTracker,
                instruments = instruments,
            )
        val riskEngine = RiskEngine(rules + preTradeRules, haltRules, positions, riskState)
        bus.subscribe<com.qkt.events.RiskEvent.Halted> { halts.add(it) }

        collector =
            EquityCurveCollector(
                cadence = this.cadence,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = strategies.map { it.first },
                startingBalance = startingBalance,
            )

        val holder = arrayOfNulls<TradingPipeline>(1)
        pipeline =
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
                    val risk = holder[0]?.orderManager?.riskUsdFor(trade.orderId)
                    tradeRecords.add(TradeRecord(trade, realized, strategyId, risk))
                    tape.add(TapeEvent.Filled(currentTimestamp, trade, realized, strategyId))
                },
                onRejected = { e ->
                    rejections.add(e)
                    tape.add(TapeEvent.Rejected(currentTimestamp, e.request.symbol, e.reason))
                },
                onCandle = { barsClosed++ },
                instruments = instruments,
                commissionBook = commissionBook,
                latencyEnabled = latencyEnabled,
            )
        holder[0] = pipeline

        if (source !== NullMarketSource && warmupSpec !is WarmupSpec.None && symbols.isNotEmpty()) {
            IndicatorWarmer(source, pipeline).warmup(
                symbols = symbols,
                spec = warmupSpec,
                now = Instant.ofEpochMilli(initialTimestamp),
            )
        }

        bus.subscribe<com.qkt.events.SignalEvent> { e ->
            tape.add(TapeEvent.SignalEmitted(currentTimestamp, e.signal))
        }
    }

    /** Pull and ingest ticks until [stop] returns true after a tick, or the feed drains. */
    fun advanceUntil(stop: () -> Boolean) {
        if (exhausted) return
        while (true) {
            val tick = feed.next()
            if (tick == null) {
                exhausted = true
                feed.close()
                break
            }
            currentTimestamp = tick.timestamp
            ticksIngested++
            clock.time = tick.timestamp
            pipeline.ingest(tick)
            if (stop()) break
        }
    }

    /** Advance to the end of the feed. */
    fun advanceToEnd() = advanceUntil { false }

    /** Advance to the end and return the result — the batch-backtest convenience path. */
    fun runToEnd(): BacktestResult {
        advanceToEnd()
        return snapshot()
    }

    /** Trades filled so far. */
    val tradeCount: Int get() = tradeRecords.size

    /** Account equity as of the last ingested tick: starting balance + realized + unrealized. */
    fun equity(): BigDecimal = startingBalance + pnl.realizedTotal() + pnl.unrealizedTotal()

    /** Currently open (non-flat) positions keyed by symbol. */
    fun openPositions(): Map<String, Position> = positions.allPositions().filterValues { it.quantity.signum() != 0 }

    /** Build a [BacktestResult] from current state — valid mid-replay or at end. */
    fun snapshot(): BacktestResult {
        val annualizationFactor = annualizationFactorFor(collector.globalMetrics())
        val globalReport =
            ReportBuilder.buildGlobal(
                trades = tradeRecords,
                equityCurve = collector.global(),
                finalRealized = pnl.realizedTotal(),
                finalUnrealized = pnl.unrealizedTotal(),
                annualizationFactor = annualizationFactor,
                metrics = collector.globalMetrics(),
                commissionPaid = commissionBook.total(),
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
                        metrics = collector.metricsFor(id),
                        commissionPaid = commissionBook.totalFor(id),
                    )
            }
        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            halts = halts.toList(),
            finalPositions = positions.allPositions(),
            global = globalReport,
            perStrategy = perStrategy,
            cadence = cadence,
            latencyReport = if (latencyEnabled) pipeline.latency.snapshot() else null,
        )
    }

    /** Returns tape events accumulated since the last drain, then clears the buffer. */
    fun drainTape(): List<TapeEvent> {
        val out = tape.toList()
        tape.clear()
        return out
    }

    override fun close() = feed.close()

    private fun annualizationFactorFor(metrics: EquityMetrics): BigDecimal {
        if (cadence == SampleCadence.CANDLE_CLOSE && candleWindow != null) {
            return calendar.tradingPeriodsPerYear(candleWindow)
        }
        if (metrics.count < 2) return BigDecimal("252")
        val first = metrics.firstTimestamp() ?: return BigDecimal("252")
        val spanMs = metrics.lastTimestamp() - first
        if (spanMs <= 0L) return BigDecimal("252")
        val avgIntervalMs = BigDecimal(spanMs).divide(BigDecimal(metrics.count - 1), Money.CONTEXT)
        val msPerYear = BigDecimal("31557600000")
        return msPerYear.divide(avgIntervalMs, Money.CONTEXT)
    }
}
