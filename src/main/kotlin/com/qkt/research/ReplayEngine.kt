package com.qkt.research

import com.qkt.accounting.AccountingConfig
import com.qkt.app.TradingPipeline
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.SampleCadence
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.engine.Engine
import com.qkt.events.RiskEvent
import com.qkt.events.SignalEvent
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.BookBalanceView
import com.qkt.pnl.SwapFinancingBook
import com.qkt.positions.Position
import com.qkt.risk.RiskRule
import com.qkt.risk.RunawayBreaker
import com.qkt.risk.StrategyRiskLimits
import com.qkt.risk.book.wireBookReservations
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal

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
    private var feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val replayEndTimestamp: Long? = null,
    source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    warmupSpec: WarmupSpec = WarmupSpec.None,
    symbols: List<String> = emptyList(),
    cadence: SampleCadence? = null,
    private val startingBalance: BigDecimal = BigDecimal.ZERO,
    /** Optional per-strategy capital bases; absent ids inherit [startingBalance]. */
    private val startingBalances: Map<String, BigDecimal> = emptyMap(),
    private val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
    private val totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
    private val strategyRiskLimits: Map<String, StrategyRiskLimits> = emptyMap(),
    /** Portfolio CAPITAL for `RISK OF BOOK` sizing; null outside portfolio backtests. */
    private val bookCapital: BigDecimal? = null,
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    private val accountingConfig: AccountingConfig = AccountingConfig(),
    private val tradedSymbols: List<String> = symbols,
    private val bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
    brokerKind: BrokerKind = BrokerKind.PAPER,
    private val executionConfig: ExecutionSimulationConfig = ExecutionSimulationConfig.forBrokerKind(brokerKind),
    private val pacerLedger: com.qkt.risk.PacerLedger = com.qkt.risk.PacerLedger(),
    private val pacerCooldownDurationMs: Long? = null,
    private val pacerCooldownAfterConsecutive: Int = 1,
    private val pacerCooldownDurationMsFor: ((String) -> Long?)? = null,
    private val pacerCooldownAfterConsecutiveFor: ((String) -> Int)? = null,
    private val maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
    private val maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
    private val priceCollarFrac: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
    private val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
    /**
     * Per-strategy gate for portfolio regime rules. Default always-true keeps single-strategy
     * backtests unchanged; portfolio backtests supply a gate driven by [PortfolioGate].
     */
    private val gateFor: (String) -> Boolean = { true },
    /**
     * Hook called before strategies process a closed candle. Portfolio backtests use this to
     * advance [com.qkt.dsl.portfolio.PortfolioGate] state so the gate is current for the bar being evaluated.
     */
    private val preCandle: (com.qkt.marketdata.Candle) -> Unit = {},
    /**
     * Current regime-weight vector for [com.qkt.risk.book.AllocationMethod.REGIME_WEIGHTED].
     * Updated once per closed candle before strategy handlers run, so order scaling uses the
     * current bar's allocation.
     */
    private val regimeWeights: () -> Map<String, BigDecimal> = { emptyMap() },
    /**
     * `--bars` research tier: fill triggered Stop/Limit exits at their own price level
     * rather than the synthetic bar extreme the triggering tick carries. See
     * [com.qkt.broker.PaperBroker.fillAtTriggerPrice]. Off (and unused) on the tick path.
     */
    private val barFills: Boolean = false,
    /**
     * Tick-resolved fills: when both are non-null, the `--bars` replay is driven by these bars but
     * fills resolve on real ticks for any bar where one is possible (see [BarResolvedFeed]). The
     * predicate is bound to this engine's own [OrderManager], so the result is byte-identical to a
     * full-tick replay. Null on every other path (normal ticks, plain `--bars`, fan-out sweep).
     */
    tickResolvedBars: Map<String, Sequence<com.qkt.marketdata.Candle>>? = null,
    tickSlicer: ((String, Long, Long) -> Sequence<Tick>)? = null,
    private val enforceLiveBreakers: Boolean = false,
    private val runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
    private val runawayRoundTripWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_ROUND_TRIP_WINDOW_MS,
    private val runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
    private val runawayRejectionWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_REJECTION_WINDOW_MS,
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
    private val books = ReplayBooks(instruments, accountingConfig, markTimestamp = { currentTimestamp })
    private val positions = books.positions
    private val recorder = ReplayRecorder(initialTimestamp)
    private val pipeline: TradingPipeline
    private val swapBook: SwapFinancingBook
    private val results: ReplayResultBuilder

    init {
        require(this.cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
            "SampleCadence.CANDLE_CLOSE requires candleWindow"
        }
        val ids = SequentialIdGenerator.forSession(strategies.map { it.first })
        val sequencer = MonotonicSequenceGenerator()
        val strategyPnL = books.strategyPnL
        for ((id, _) in strategies) {
            strategyPnL.setStartingBalance(id, startingBalances[id] ?: startingBalance)
        }
        val bus = EventBus(clock, sequencer)
        recorder.subscribe(bus)
        val engine = Engine(bus, books.priceTracker)
        val candleHub = CandleHub()
        val warmup = ReplayWarmup(strategies, source, candleHub, initialTimestamp, warmupSpec, tradedSymbols)

        val dslStrategies = strategies.mapNotNull { (_, s) -> s as? DslCompiledStrategy }
        warnQuoteFieldReads(dslStrategies)
        val brokerSymbols = brokerSymbolsOf(dslStrategies)
        requireReplaySymbolsResolvable(tradedSymbols + brokerSymbols.values.flatten(), books.accounting, instruments)
        val broker =
            replayBroker(
                executionConfig,
                bus,
                clock,
                books.priceTracker,
                instruments,
                barFills,
                calendar,
                brokerSymbols,
            )
        val risk =
            ReplayRisk(
                rules = rules,
                haltRules = haltRules,
                strategyIds = strategies.map { it.first },
                books = books,
                clock = clock,
                bus = bus,
                instruments = instruments,
                startingBalance = startingBalance,
                startingBalances = startingBalances,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
                strategyRiskLimits = strategyRiskLimits,
                pacerLedger = pacerLedger,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                candleWindow = candleWindow,
                calendar = calendar,
                bookRiskConfig = bookRiskConfig,
            )
        val riskState = risk.riskState
        val bookRiskController = risk.bookRiskController
        bus.subscribe<RiskEvent.Halted> { recorder.halts.add(it) }

        val analytics =
            ReplayAnalytics(
                cadence = this.cadence,
                bus = bus,
                books = books,
                strategyIds = strategies.map { it.first },
                strategyCount = strategies.size,
                startingBalance = startingBalance,
                symbols = symbols,
                initialTimestamp = initialTimestamp,
                instruments = instruments,
                bookRiskController = bookRiskController,
            )

        val holder = arrayOfNulls<TradingPipeline>(1)
        pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = books.priceTracker,
                positions = positions,
                pnl = books.pnl,
                strategyPositions = books.strategyPositions,
                strategyPnL = strategyPnL,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = strategies,
                riskEngine = risk.riskEngine,
                riskState = riskState,
                positionMode = { executionConfig.positionMode },
                runawayBreaker =
                    RunawayBreaker(
                        clock = clock,
                        riskState = riskState,
                        maxRoundTrips = runawayMaxRoundTrips,
                        roundTripWindowMs = runawayRoundTripWindowMs,
                        maxRejections = runawayMaxRejections,
                        rejectionWindowMs = runawayRejectionWindowMs,
                        enforce = enforceLiveBreakers,
                        onTrip = recorder.breakerTrips::add,
                    ),
                pacerLedger = pacerLedger,
                pacerCooldownDurationMs = pacerCooldownDurationMs,
                pacerCooldownAfterConsecutive = pacerCooldownAfterConsecutive,
                pacerCooldownDurationMsFor = pacerCooldownDurationMsFor,
                pacerCooldownAfterConsecutiveFor = pacerCooldownAfterConsecutiveFor,
                bookScaleFor = { id -> bookRiskController?.state()?.scaleFor(id) ?: BigDecimal.ONE },
                // Same definition as the live PortfolioDeployer binding: CAPITAL + the sum of
                // per-child realized (NOT the netted account realized, which diverges when
                // children cross on one symbol) — sizing stays parity-exact.
                bookBalance =
                    bookCapital?.let { capital ->
                        BookBalanceView {
                            strategies.fold(capital) { acc, (id, _) -> acc.add(strategyPnL.realizedFor(id)) }
                        }
                    },
                mode = Mode.BACKTEST,
                replayCandleCloseGraceMs = executionConfig.candleCloseGraceMs,
                replayHeartbeatIntervalMs = executionConfig.heartbeatIntervalMs,
                calendar = calendar,
                source = source,
                candleWindow = candleWindow,
                candleHub = candleHub,
                onAccountedFill = { trade, converted, strategyId, fillState ->
                    val orderManager = holder[0]?.orderManager
                    recorder.recordFill(currentTimestamp, trade, converted, strategyId, fillState, orderManager)
                },
                onRejected = { e -> recorder.recordRejection(currentTimestamp, e) },
                onCandle = { barsClosed++ },
                preCandle = { candle ->
                    this.preCandle(candle)
                    bookRiskController?.setRegimeWeights(regimeWeights())
                },
                gateFor = gateFor,
                instruments = instruments,
                commissionBook = books.commissionBook,
                accounting = books.accounting,
                latencyEnabled = latencyEnabled,
            )
        // Same reservation lifecycle as live, registered after the pipeline so a fill is already in
        // positions when it is marked. Without it the backtest would never release a reservation.
        bookRiskController?.let { controller -> wireBookReservations(bus, controller) }
        holder[0] = pipeline
        swapBook =
            SwapFinancingBook(
                instruments = instruments,
                strategyPositions = books.strategyPositions,
                accounting = books.accounting,
                prices = books.priceTracker,
                strategyIds = strategies.map { it.first },
                symbols = tradedSymbols + brokerSymbols.values.flatten(),
            )
        subscribeHaltKillSwitch(bus, pipeline, strategies, books.strategyPositions, ids, clock, bookCapital)

        // Tick-resolved fills: replace the bar feed with one that loads real ticks for fill-possible
        // bars, deciding via this engine's own OrderManager. Built here, after the pipeline exists.
        if (tickResolvedBars != null && tickSlicer != null) {
            feed = BarResolvedFeed(tickResolvedBars, tickSlicer, ::intrabarFill, replayEndTimestamp)
        }

        warmup.warm(pipeline)

        bus.subscribe<SignalEvent> { e -> recorder.recordSignal(currentTimestamp, e.signal) }

        results =
            ReplayResultBuilder(
                strategies = strategies,
                books = books,
                recorder = recorder,
                analytics = analytics,
                swapBook = swapBook,
                pipeline = pipeline,
                instruments = instruments,
                cadence = this.cadence,
                candleWindow = candleWindow,
                calendar = calendar,
                latencyEnabled = latencyEnabled,
                enforceLiveBreakers = enforceLiveBreakers,
                runawayMaxRoundTrips = runawayMaxRoundTrips,
                runawayRoundTripWindowMs = runawayRoundTripWindowMs,
                runawayMaxRejections = runawayMaxRejections,
                runawayRejectionWindowMs = runawayRejectionWindowMs,
            )
    }

    /**
     * Apply one already-decoded tick to this engine's pipeline. The fan-out sweep driver pulls each
     * tick once from a single shared feed and pushes it into every per-combo engine via this method,
     * so the decode happens once instead of once per combo. The internal feed loop calls it too, so
     * pushed and pulled ticks take an identical path.
     */
    fun ingest(tick: Tick) {
        if (ticksIngested > 0L) {
            swapBook.accrueBetween(currentTimestamp, tick.timestamp) { strategyId, boundaryMs, amount ->
                currentTimestamp = boundaryMs
                clock.time = boundaryMs
                pipeline.applyFinancing(strategyId, amount)
            }
        }
        currentTimestamp = tick.timestamp
        ticksIngested++
        clock.time = tick.timestamp
        pipeline.ingest(tick)
    }

    // Tick-resolved fills: how few real ticks must this bar feed to stay byte-identical? Delegates to
    // this engine's OrderManager so the decision matches the orders the run actually holds.
    private fun intrabarFill(
        symbol: String,
        low: BigDecimal,
        high: BigDecimal,
        maxHalfSpread: BigDecimal,
    ): com.qkt.app.IntrabarFill = pipeline.orderManager.intrabarFill(symbol, low, high, maxHalfSpread)

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
            ingest(tick)
            if (stop()) break
        }
    }

    /** Advance to the end of the feed. */
    fun advanceToEnd() = advanceUntil { false }

    /** Advance to the end and return the result — the batch-backtest convenience path. */
    fun runToEnd(): BacktestResult {
        advanceToEnd()
        flushCompletedReplayBoundary()
        return snapshot()
    }

    private fun flushCompletedReplayBoundary() {
        val window = candleWindow ?: return
        val replayEnd = replayEndTimestamp ?: return
        if (ticksIngested == 0L) return
        val boundary = window.windowEndFor(currentTimestamp)
        if (boundary > replayEnd) return
        currentTimestamp = boundary
        clock.time = boundary
        pipeline.flushReplayCandles(boundary)
    }

    /** Trades filled so far. */
    val tradeCount: Int get() = recorder.tradeRecords.size

    /** Account equity as of the last ingested tick: starting balance + realized + unrealized. */
    fun equity(): BigDecimal = startingBalance + books.pnl.realizedTotal() + books.pnl.unrealizedTotal()

    /** Currently open (non-flat) positions keyed by symbol. */
    fun openPositions(): Map<String, Position> = positions.allPositions().filterValues { it.quantity.signum() != 0 }

    /**
     * Sign of the net position on [symbol]: -1 short, 1 long, 0 flat/unknown. Cheap (one map
     * lookup) — [com.qkt.marketdata.source.BarTickFeed] consults it once per synthesized bar to
     * emit the open position's adverse extreme first.
     */
    fun positionSign(symbol: String): Int = positions.positionFor(symbol)?.quantity?.signum() ?: 0

    /** Build a [BacktestResult] from current state — valid mid-replay or at end. */
    fun snapshot(): BacktestResult = results.build(ticksIngested)

    /** Returns tape events accumulated since the last drain, then clears the buffer. */
    fun drainTape(): List<TapeEvent> = recorder.drainTape()

    override fun close() = feed.close()
}
