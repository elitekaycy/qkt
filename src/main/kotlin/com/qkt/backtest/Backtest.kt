package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.BarTickFeed
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.risk.RiskRule
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal

class Backtest(
    private val strategies: List<Pair<String, Strategy>>,
    private val rules: List<RiskRule> = emptyList(),
    /** Account-protection halts; build via [com.qkt.risk.HaltRules.standard] for live parity. */
    private val haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    private val warmupSpec: WarmupSpec = WarmupSpec.None,
    private val symbols: List<String> = emptyList(),
    cadence: SampleCadence? = null,
    private val startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    /** Optional per-strategy capital bases; absent ids inherit [startingBalance]. */
    private val startingBalances: Map<String, BigDecimal> = emptyMap(),
    private val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
    private val totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
    private val strategyRiskLimits: Map<String, com.qkt.risk.StrategyRiskLimits> = emptyMap(),
    /** Portfolio CAPITAL for `RISK OF BOOK` sizing; null outside portfolio backtests. */
    private val bookCapital: BigDecimal? = null,
    private val instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
    private val accountingConfig: com.qkt.accounting.AccountingConfig = com.qkt.accounting.AccountingConfig(),
    private val tradedSymbols: List<String> = symbols,
    private val bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
    private val brokerKind: BrokerKind = BrokerKind.PAPER,
    private val executionConfig: ExecutionSimulationConfig = ExecutionSimulationConfig.forBrokerKind(brokerKind),
    private val pacerLedger: com.qkt.risk.PacerLedger = com.qkt.risk.PacerLedger(),
    private val pacerCooldownDurationMs: Long? = null,
    private val pacerCooldownAfterConsecutive: Int = 1,
    private val pacerCooldownDurationMsFor: ((String) -> Long?)? = null,
    private val pacerCooldownAfterConsecutiveFor: ((String) -> Int)? = null,
    private val maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
    private val maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
    private val priceCollarFrac: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
    /** See [com.qkt.app.TradingPipeline.latencyEnabled]; defaults to the env-var read. */
    private val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
    /**
     * Per-strategy gate for portfolio regime rules. Default always-true keeps single-strategy
     * backtests unchanged.
     */
    private val gateFor: (String) -> Boolean = { true },
    /**
     * Hook called before strategies process a closed candle. Portfolio backtests use this to
     * advance the portfolio gate state so the gate is current for the bar being evaluated.
     */
    private val preCandle: (com.qkt.marketdata.Candle) -> Unit = {},
    /**
     * Current regime-weight vector for [com.qkt.risk.book.AllocationMethod.REGIME_WEIGHTED].
     * Updated once per closed candle before strategy handlers run.
     */
    private val regimeWeights: () -> Map<String, BigDecimal> = { emptyMap() },
    /**
     * `--bars` research tier: fill triggered Stop/Limit exits at their trigger level, not
     * the synthetic bar extreme. See [com.qkt.broker.PaperBroker.fillAtTriggerPrice].
     */
    private val barFills: Boolean = false,
    /**
     * Tick-resolved fills (`--bars --tick-fills`): bars drive signals, but fills resolve on real
     * ticks for any bar where one is possible — byte-identical to a full-tick replay. Both null on
     * every other path. See [com.qkt.research.BarResolvedFeed].
     */
    private val tickResolvedBars: Map<String, Sequence<com.qkt.marketdata.Candle>>? = null,
    private val tickSlicer: ((String, Long, Long) -> Sequence<Tick>)? = null,
    /**
     * Late-bound engine slot for position-aware bar synthesis: a bar-synthesized [feed] closes over
     * this holder to read net position signs from the engine that ends up pulling it, so each bar
     * can emit the open position's adverse extreme first (see
     * [com.qkt.marketdata.source.BarTickFeed]). Bound by [toEngine] only when the engine drives
     * this backtest's own feed — an externally-driven engine (sweep fan-out) never binds it, so a
     * shared feed stays on the flat-default ordering.
     */
    private val engineHolder: Array<com.qkt.research.ReplayEngine?>? = null,
    /** When true, replay halts on the same runaway-breaker trip as live execution. */
    private val enforceLiveBreakers: Boolean = false,
    private val runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
    private val runawayRoundTripWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_ROUND_TRIP_WINDOW_MS,
    private val runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
    private val runawayRejectionWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_REJECTION_WINDOW_MS,
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
        haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
        ticks: List<Tick>,
        candleWindow: TimeWindow? = null,
        initialTimestamp: Long = 0L,
        cadence: SampleCadence? = null,
        startingBalance: java.math.BigDecimal = java.math.BigDecimal.ZERO,
        /** Optional per-strategy capital bases; absent ids inherit [startingBalance]. */
        startingBalances: Map<String, BigDecimal> = emptyMap(),
        dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
        totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
        strategyRiskLimits: Map<String, com.qkt.risk.StrategyRiskLimits> = emptyMap(),
        bookCapital: BigDecimal? = null,
        instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
        accountingConfig: com.qkt.accounting.AccountingConfig = com.qkt.accounting.AccountingConfig(),
        tradedSymbols: List<String> = emptyList(),
        bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
        brokerKind: BrokerKind = BrokerKind.PAPER,
        executionConfig: ExecutionSimulationConfig = ExecutionSimulationConfig.forBrokerKind(brokerKind),
        pacerLedger: com.qkt.risk.PacerLedger = com.qkt.risk.PacerLedger(),
        pacerCooldownDurationMs: Long? = null,
        pacerCooldownAfterConsecutive: Int = 1,
        pacerCooldownDurationMsFor: ((String) -> Long?)? = null,
        pacerCooldownAfterConsecutiveFor: ((String) -> Int)? = null,
        maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
        maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
        priceCollarFrac: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
        latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
        gateFor: (String) -> Boolean = { true },
        preCandle: (com.qkt.marketdata.Candle) -> Unit = {},
        regimeWeights: () -> Map<String, BigDecimal> = { emptyMap() },
        enforceLiveBreakers: Boolean = false,
        runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
        runawayRoundTripWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_ROUND_TRIP_WINDOW_MS,
        runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
        runawayRejectionWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_REJECTION_WINDOW_MS,
    ) : this(
        strategies = strategies,
        rules = rules,
        haltRules = haltRules,
        feed = HistoricalTickFeed(ticks),
        candleWindow = candleWindow,
        initialTimestamp = initialTimestamp,
        cadence = cadence,
        startingBalance = startingBalance,
        startingBalances = startingBalances,
        dailyDdBasis = dailyDdBasis,
        totalDdBasis = totalDdBasis,
        strategyRiskLimits = strategyRiskLimits,
        bookCapital = bookCapital,
        instruments = instruments,
        accountingConfig = accountingConfig,
        tradedSymbols = tradedSymbols.ifEmpty { ticks.map { it.symbol }.distinct() },
        bookRiskConfig = bookRiskConfig,
        brokerKind = brokerKind,
        executionConfig = executionConfig,
        pacerLedger = pacerLedger,
        pacerCooldownDurationMs = pacerCooldownDurationMs,
        pacerCooldownAfterConsecutive = pacerCooldownAfterConsecutive,
        pacerCooldownDurationMsFor = pacerCooldownDurationMsFor,
        pacerCooldownAfterConsecutiveFor = pacerCooldownAfterConsecutiveFor,
        maxOrderQty = maxOrderQty,
        maxOrderNotional = maxOrderNotional,
        priceCollarFrac = priceCollarFrac,
        latencyEnabled = latencyEnabled,
        gateFor = gateFor,
        preCandle = preCandle,
        regimeWeights = regimeWeights,
        enforceLiveBreakers = enforceLiveBreakers,
        runawayMaxRoundTrips = runawayMaxRoundTrips,
        runawayRoundTripWindowMs = runawayRoundTripWindowMs,
        runawayMaxRejections = runawayMaxRejections,
        runawayRejectionWindowMs = runawayRejectionWindowMs,
    )

    /**
     * Build the replay engine for this backtest, optionally driven by an external [feedOverride]
     * (defaults to this backtest's own feed). A fan-out sweep builds one engine per combo with an
     * empty feed and pushes a single shared decoded feed into all of them via [ReplayEngine.ingest],
     * so the market data is decoded once instead of once per combo.
     */
    fun toEngine(feedOverride: TickFeed = feed): com.qkt.research.ReplayEngine {
        val engine =
            com.qkt.research.ReplayEngine(
                strategies = strategies,
                rules = rules,
                haltRules = haltRules,
                feed = feedOverride,
                candleWindow = candleWindow,
                initialTimestamp = initialTimestamp,
                source = source,
                calendar = calendar,
                warmupSpec = warmupSpec,
                symbols = symbols,
                cadence = cadence,
                startingBalance = startingBalance,
                startingBalances = startingBalances,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
                strategyRiskLimits = strategyRiskLimits,
                bookCapital = bookCapital,
                instruments = instruments,
                accountingConfig = accountingConfig,
                tradedSymbols = tradedSymbols,
                bookRiskConfig = bookRiskConfig,
                brokerKind = brokerKind,
                executionConfig = executionConfig,
                pacerLedger = pacerLedger,
                pacerCooldownDurationMs = pacerCooldownDurationMs,
                pacerCooldownAfterConsecutive = pacerCooldownAfterConsecutive,
                pacerCooldownDurationMsFor = pacerCooldownDurationMsFor,
                pacerCooldownAfterConsecutiveFor = pacerCooldownAfterConsecutiveFor,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                latencyEnabled = latencyEnabled,
                gateFor = gateFor,
                preCandle = preCandle,
                regimeWeights = regimeWeights,
                enforceLiveBreakers = enforceLiveBreakers,
                runawayMaxRoundTrips = runawayMaxRoundTrips,
                runawayRoundTripWindowMs = runawayRoundTripWindowMs,
                runawayMaxRejections = runawayMaxRejections,
                runawayRejectionWindowMs = runawayRejectionWindowMs,
                barFills = barFills,
                tickResolvedBars = tickResolvedBars,
                tickSlicer = tickSlicer,
            )
        // Bind only when the engine pulls this backtest's own feed: a position-aware feed is sound
        // exactly when one engine consumes it. Externally-driven engines (fan-out) leave it unbound.
        if (feedOverride === feed) engineHolder?.set(0, engine)
        return engine
    }

    fun run(): BacktestResult = toEngine().runToEnd()

    /** This backtest's market feed, so a fan-out sweep can share one decode across many engines. */
    internal fun detachFeed(): TickFeed = feed

    companion object {
        fun fromStore(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
            calendar: TradingCalendar = TradingCalendar.crypto(),
            store: DataStore,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            cadence: SampleCadence? = null,
            startingBalance: BigDecimal = BigDecimal.ZERO,
            /** Optional per-strategy capital bases; absent ids inherit [startingBalance]. */
            startingBalances: Map<String, BigDecimal> = emptyMap(),
            dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
            totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
            strategyRiskLimits: Map<String, com.qkt.risk.StrategyRiskLimits> = emptyMap(),
            bookCapital: BigDecimal? = null,
            instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
            accountingConfig: com.qkt.accounting.AccountingConfig = com.qkt.accounting.AccountingConfig(),
            tradedSymbols: List<String> = request.symbols,
            /**
             * Phase 25A: optional pre-fetched bar store (populated by `qkt fetch`). When
             * present, `LocalMarketSource.bars()` reads from it instead of aggregating
             * from ticks for any day fully covered. Falls back to ticks for missing days.
             */
            barStore: com.qkt.marketdata.store.LocalBarStore? = null,
            brokerKind: BrokerKind = BrokerKind.PAPER,
            executionConfig: ExecutionSimulationConfig = ExecutionSimulationConfig.forBrokerKind(brokerKind),
            bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
            pacerLedger: com.qkt.risk.PacerLedger = com.qkt.risk.PacerLedger(),
            pacerCooldownDurationMs: Long? = null,
            pacerCooldownAfterConsecutive: Int = 1,
            pacerCooldownDurationMsFor: ((String) -> Long?)? = null,
            pacerCooldownAfterConsecutiveFor: ((String) -> Int)? = null,
            maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
            maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
            priceCollarFrac: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
            forceBars: Boolean = false,
            barWindows: Map<String, TimeWindow> = emptyMap(),
            binaryBarStore: com.qkt.marketdata.store.BinaryBarStore? = null,
            tickFills: Boolean = false,
            gateFor: (String) -> Boolean = { true },
            preCandle: (com.qkt.marketdata.Candle) -> Unit = {},
            regimeWeights: () -> Map<String, BigDecimal> = { emptyMap() },
            enforceLiveBreakers: Boolean = false,
            runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
            runawayRoundTripWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_ROUND_TRIP_WINDOW_MS,
            runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
            runawayRejectionWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_REJECTION_WINDOW_MS,
        ): Backtest {
            val (from, to) = store.resolveRange(request)
            val resolved = MarketRequest(symbols = request.symbols, from = from, to = to)
            val localSource =
                LocalMarketSource(
                    store,
                    FixedClock(time = to.toEpochMilli()),
                    barStore = barStore,
                    // Only the `--bars` research tier reads the binary bar store; normal runs use
                    // ticks (or the fetched CSV bar store for bars-only venues), unchanged.
                    binaryBarStore = if (forceBars) binaryBarStore else null,
                )
            // MACRO: streams (daily yields/real rates) read from the macro store via a point-in-time
            // source; everything else falls through to the tick store. Non-MACRO runs are unchanged.
            val source: MarketSource =
                if (request.symbols.any { it.startsWith("MACRO:") }) {
                    CompositeMarketSource(
                        routes =
                            listOf(
                                SymbolPattern.prefix("MACRO:") to MacroMarketSource(MacroSeriesStore(store.root)),
                            ),
                        fallback = localSource,
                    )
                } else {
                    localSource
                }
            return fromSource(
                strategies = strategies,
                rules = rules,
                haltRules = haltRules,
                calendar = calendar,
                source = source,
                request = resolved,
                candleWindow = candleWindow,
                cadence = cadence,
                startingBalance = startingBalance,
                startingBalances = startingBalances,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
                strategyRiskLimits = strategyRiskLimits,
                bookCapital = bookCapital,
                instruments = instruments,
                accountingConfig = accountingConfig,
                tradedSymbols = tradedSymbols,
                brokerKind = brokerKind,
                executionConfig = executionConfig,
                bookRiskConfig = bookRiskConfig,
                pacerLedger = pacerLedger,
                pacerCooldownDurationMs = pacerCooldownDurationMs,
                pacerCooldownAfterConsecutive = pacerCooldownAfterConsecutive,
                pacerCooldownDurationMsFor = pacerCooldownDurationMsFor,
                pacerCooldownAfterConsecutiveFor = pacerCooldownAfterConsecutiveFor,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                forceBars = forceBars,
                barWindows = barWindows,
                tickFills = tickFills,
                gateFor = gateFor,
                preCandle = preCandle,
                regimeWeights = regimeWeights,
                enforceLiveBreakers = enforceLiveBreakers,
                runawayMaxRoundTrips = runawayMaxRoundTrips,
                runawayRoundTripWindowMs = runawayRoundTripWindowMs,
                runawayMaxRejections = runawayMaxRejections,
                runawayRejectionWindowMs = runawayRejectionWindowMs,
            )
        }

        fun fromSource(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            haltRules: List<com.qkt.risk.HaltRule> = emptyList(),
            calendar: TradingCalendar = TradingCalendar.crypto(),
            source: MarketSource,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            warmupSpec: WarmupSpec = WarmupSpec.None,
            cadence: SampleCadence? = null,
            startingBalance: BigDecimal = BigDecimal.ZERO,
            /** Optional per-strategy capital bases; absent ids inherit [startingBalance]. */
            startingBalances: Map<String, BigDecimal> = emptyMap(),
            dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
            totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
            strategyRiskLimits: Map<String, com.qkt.risk.StrategyRiskLimits> = emptyMap(),
            bookCapital: BigDecimal? = null,
            instruments: com.qkt.instrument.InstrumentRegistry = com.qkt.instrument.NoopInstrumentRegistry,
            accountingConfig: com.qkt.accounting.AccountingConfig = com.qkt.accounting.AccountingConfig(),
            tradedSymbols: List<String> = request.symbols,
            brokerKind: BrokerKind = BrokerKind.PAPER,
            executionConfig: ExecutionSimulationConfig = ExecutionSimulationConfig.forBrokerKind(brokerKind),
            bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
            pacerLedger: com.qkt.risk.PacerLedger = com.qkt.risk.PacerLedger(),
            pacerCooldownDurationMs: Long? = null,
            pacerCooldownAfterConsecutive: Int = 1,
            pacerCooldownDurationMsFor: ((String) -> Long?)? = null,
            pacerCooldownAfterConsecutiveFor: ((String) -> Int)? = null,
            maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
            maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
            priceCollarFrac: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
            forceBars: Boolean = false,
            barWindows: Map<String, TimeWindow> = emptyMap(),
            tickFills: Boolean = false,
            gateFor: (String) -> Boolean = { true },
            preCandle: (com.qkt.marketdata.Candle) -> Unit = {},
            regimeWeights: () -> Map<String, BigDecimal> = { emptyMap() },
            enforceLiveBreakers: Boolean = false,
            runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
            runawayRoundTripWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_ROUND_TRIP_WINDOW_MS,
            runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
            runawayRejectionWindowMs: Long = com.qkt.risk.RunawayBreaker.DEFAULT_REJECTION_WINDOW_MS,
        ): Backtest {
            require(
                MarketSourceCapability.TICKS in source.capabilities ||
                    MarketSourceCapability.BARS in source.capabilities,
            ) {
                "Backtest requires a MarketSource with TICKS or BARS; ${source.name} has ${source.capabilities}"
            }
            val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
            val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
            val range = TimeRange(from, to)
            // Late-bound engine slot: bar-synthesized feeds read net position signs from whichever
            // engine ends up pulling this backtest's feed (bound in toEngine), so each bar emits the
            // open position's adverse extreme first. Unbound (fan-out shared feeds), the sign reads
            // 0 and the feed keeps the flat-default Low-first order.
            val engineHolder = arrayOfNulls<com.qkt.research.ReplayEngine>(1)
            val positionSign: (String) -> Int = { sym -> engineHolder[0]?.positionSign(sym) ?: 0 }
            val perSymbolFeeds: List<TickFeed> =
                request.symbols.map { sym ->
                    replayFeed(source, sym, range, barWindows[sym] ?: candleWindow, forceBars, positionSign)
                }
            val feed: TickFeed =
                if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
            // Tick-resolved fills: the engine drives off these bars but loads real ticks for any bar
            // a fill could land in. The slice is filtered half-open [from, to) so every tick belongs
            // to exactly one bar regardless of TimeRange boundary semantics.
            val tickResolvedBars: Map<String, Sequence<com.qkt.marketdata.Candle>>? =
                if (tickFills) {
                    request.symbols.associateWith { sym ->
                        source.bars(
                            sym,
                            barWindows[sym] ?: candleWindow ?: error("--tick-fills needs a candle window"),
                            range,
                        )
                    }
                } else {
                    null
                }
            val tickSlicer: ((String, Long, Long) -> Sequence<Tick>)? =
                if (tickFills) {
                    { sym, fromMs, toMs -> source.tickSlice(sym, fromMs, toMs) }
                } else {
                    null
                }
            return Backtest(
                strategies = strategies,
                rules = rules,
                haltRules = haltRules,
                calendar = calendar,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = from.toEpochMilli(),
                source = source,
                warmupSpec = warmupSpec,
                symbols = request.symbols,
                cadence = cadence,
                startingBalance = startingBalance,
                startingBalances = startingBalances,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
                strategyRiskLimits = strategyRiskLimits,
                bookCapital = bookCapital,
                instruments = instruments,
                accountingConfig = accountingConfig,
                tradedSymbols = tradedSymbols,
                brokerKind = brokerKind,
                executionConfig = executionConfig,
                bookRiskConfig = bookRiskConfig,
                pacerLedger = pacerLedger,
                pacerCooldownDurationMs = pacerCooldownDurationMs,
                pacerCooldownAfterConsecutive = pacerCooldownAfterConsecutive,
                pacerCooldownDurationMsFor = pacerCooldownDurationMsFor,
                pacerCooldownAfterConsecutiveFor = pacerCooldownAfterConsecutiveFor,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                gateFor = gateFor,
                preCandle = preCandle,
                regimeWeights = regimeWeights,
                enforceLiveBreakers = enforceLiveBreakers,
                runawayMaxRoundTrips = runawayMaxRoundTrips,
                runawayRoundTripWindowMs = runawayRoundTripWindowMs,
                runawayMaxRejections = runawayMaxRejections,
                runawayRejectionWindowMs = runawayRejectionWindowMs,
                // Tick-resolved fills use the full-tick fill model (fill at the real tick price, not
                // the trigger level): fills only ever occur on bars fed real ticks, so the bar-tier
                // fill-at-trigger-price guard is both unnecessary and wrong here.
                barFills = forceBars && !tickFills,
                tickResolvedBars = tickResolvedBars,
                tickSlicer = tickSlicer,
                engineHolder = engineHolder,
            )
        }

        /**
         * Picks the replay feed for one symbol: real recorded ticks when the source has them,
         * otherwise synthesized O->L->H->C ticks from its OHLC bars (the only path for bars-only
         * venues like crypto). Preferring ticks keeps tick-sourced backtests (e.g. MT5) byte-for-byte
         * unchanged; the bar fallback is what makes a `qkt fetch`ed crypto symbol backtest at all.
         */
        private fun replayFeed(
            source: MarketSource,
            symbol: String,
            range: TimeRange,
            window: TimeWindow?,
            forceBars: Boolean,
            positionSign: (String) -> Int = { 0 },
        ): TickFeed {
            val caps = source.capabilities
            val ticksAvailable = MarketSourceCapability.TICKS in caps
            // The `--bars` research tier forces synthesis from bars; otherwise prefer real ticks,
            // which keeps tick-sourced backtests byte-for-byte unchanged.
            if (!forceBars && ticksAvailable) {
                val iter = source.ticks(symbol, range).iterator()
                if (iter.hasNext()) {
                    val first = iter.next()
                    return SequenceTickFeed(sequenceOf(first) + iter.asSequence())
                }
            }
            // Synthesize O->L->H->C ticks from OHLC bars (the forced research tier, or the bars-only
            // fallback for venues like crypto).
            if (MarketSourceCapability.BARS in caps && window != null) {
                val iter = source.bars(symbol, window, range).iterator()
                require(iter.hasNext()) {
                    "no market data for $symbol in requested range ${range.from}..${range.to}"
                }
                return BarTickFeed(sequenceOf(iter.next()) + iter.asSequence(), positionSign)
            }
            if (forceBars) {
                error("--bars: cannot replay bars for $symbol (source has no BARS capability or no candle window)")
            }
            require(ticksAvailable) {
                "bar-based backtest for $symbol needs a candle window (timeframe) — pass candleWindow"
            }
            error("no market data for $symbol in requested range ${range.from}..${range.to}")
        }
    }
}
