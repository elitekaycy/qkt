package com.qkt.cli

import com.qkt.accounting.AccountCurrency
import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.FxMissingPolicy
import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestDataProvisioner
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionPreset
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.GatedChild
import com.qkt.backtest.ProvisionStream
import com.qkt.backtest.SlippageSpec
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.dsl.portfolio.capitalAllocations
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceHasher
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DatasetSnapshot
import com.qkt.marketdata.store.DatasetSnapshots
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.ScriptDataFetcher
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher
import com.qkt.marketdata.store.macro.FredSeriesFetcher
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import com.qkt.marketdata.store.macro.PolicyRateSeriesFetcher
import com.qkt.research.ReplayEngine
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Inputs the risk halt rules are built from; the account-balance basis is supplied per backtest. */
internal data class HaltConfig(
    val maxDailyLoss: BigDecimal,
    val maxDrawdownPct: BigDecimal?,
    val maxDailyDrawdownPct: BigDecimal?,
    val totalDdBasis: com.qkt.risk.DrawdownBasis,
    val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis,
)

/**
 * Shared backtest wiring used by `backtest`, `sweep`, `walkforward`, and (store/instruments only)
 * `research`. Centralizes data store, dukascopy auto-fetch, completeness provisioning, instrument
 * specs, candle window, and broker kind so every path produces identical results for the same combo.
 */
class BacktestContext private constructor(
    val ast: StrategyAst,
    val from: Instant,
    val to: Instant,
    val brokerKind: BrokerKind,
    val executionConfig: ExecutionSimulationConfig,
    val symbols: List<String>,
    val store: DefaultDataStore,
    val instruments: InstrumentRegistry,
    val barStore: LocalBarStore,
    val datasetEvidence: DatasetEvidence,
    val accountingConfig: AccountingConfig,
    private val candleWindow: TimeWindow?,
    private val startingBalance: BigDecimal,
    private val startingBalances: Map<String, BigDecimal> = emptyMap(),
    /** Portfolio CAPITAL for `RISK OF BOOK` sizing; null outside portfolio backtests. */
    private val bookCapital: BigDecimal? = null,
    private val haltConfig: HaltConfig,
    private val provisioner: () -> Unit,
    private val replaySymbols: List<String> = symbols,
    private val strategiesOverride: ((Map<String, String>) -> List<Pair<String, com.qkt.strategy.Strategy>>)? = null,
    private val bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
    private val perStrategyRisk: Map<String, PerStrategyRisk> = emptyMap(),
    private val maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
    private val maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
    private val priceCollarFrac: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC,
    private val forceBars: Boolean = false,
    private val barWindows: Map<String, TimeWindow> = emptyMap(),
    private val binaryBarStore: BinaryBarStore? = null,
    private val tickFills: Boolean = false,
    private val gateFor: (String) -> Boolean = { true },
    private val preCandle: (com.qkt.marketdata.Candle) -> Unit = {},
    private val regimeWeights: () -> Map<String, BigDecimal> = { emptyMap() },
    private val enforceLiveBreakers: Boolean = false,
    private val runawayMaxRoundTrips: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_ROUND_TRIPS,
    private val runawayMaxRejections: Int = com.qkt.risk.RunawayBreaker.DEFAULT_MAX_REJECTIONS,
) {
    /** Fetch + completeness-validate the data the run(s) will touch. Throws IncompleteDataException on holes. */
    fun provision() = provisioner()

    /**
     * True when fills resolve on synthesized bar ticks (`--bars` without `--tick-fills`). Sweep
     * drivers must run these per-combo ([com.qkt.backtest.sweep.BacktestSweep]) rather than through
     * the shared-feed fan-out: bar synthesis orders each bar's extremes adverse-first for the open
     * position, and positions differ per combo, so one shared tick stream cannot serve them all.
     */
    val barFills: Boolean get() = forceBars && !tickFills

    /**
     * Build a backtest for [overrides] over [range] (defaults to the full configured window).
     * For a fan-out scenario, [ast], [brokerKind], [instruments], and [startingBalance] may each
     * differ from the context default; the halt rules are re-derived from the effective balance so
     * the result is byte-identical to a standalone backtest built with those same values. A scenario
     * may NOT change the symbol set — the shared decoded feed is keyed to it (enforced below).
     */
    fun backtest(
        overrides: Map<String, String>,
        range: TimeRange = TimeRange(from, to),
        ast: StrategyAst = this.ast,
        brokerKind: BrokerKind = this.brokerKind,
        executionConfig: ExecutionSimulationConfig = this.executionConfig,
        instruments: InstrumentRegistry = this.instruments,
        startingBalance: BigDecimal = this.startingBalance,
    ): Backtest {
        require(
            ast.streams.map { it.qktSymbol }.toSet() ==
                this.ast.streams
                    .map { it.qktSymbol }
                    .toSet(),
        ) {
            "scenario strategy must declare the same streams as the base; the shared feed is keyed to them"
        }
        val strats =
            strategiesOverride?.invoke(overrides)
                ?: listOf(ast.name to AstCompiler().compile(ast, overrides))
        val effectiveExecution =
            if (executionConfig == this.executionConfig && brokerKind != this.brokerKind) {
                ExecutionSimulationConfig.forBrokerKind(brokerKind)
            } else {
                executionConfig
            }
        // The symbol's LIVE calendar, not hardwired crypto: session/range indicators
        // (PreviousDayHigh, session gates) disagree by construction otherwise. The
        // pipeline takes one calendar — resolved from the first symbol; mixed-class
        // baskets keep that limitation (divergence catalog row A9).
        val calendar =
            symbols.firstOrNull()?.let { defaultCalendars().calendarFor(it.substringAfter(':')) }
                ?: com.qkt.common.TradingCalendar
                    .crypto()
        val haltRules =
            com.qkt.risk.HaltRules.standard(
                maxDailyLoss = haltConfig.maxDailyLoss,
                maxDrawdownPct = haltConfig.maxDrawdownPct,
                maxDailyDrawdownPct = haltConfig.maxDailyDrawdownPct,
                totalDdBasis = haltConfig.totalDdBasis,
                startingBalance = startingBalance,
            )
        return Backtest.fromStore(
            strategies = strats,
            haltRules = haltRules,
            calendar = calendar,
            store = store,
            request = MarketRequest(symbols = replaySymbols, from = range.from, to = range.to),
            candleWindow = candleWindow,
            startingBalance = startingBalance,
            startingBalances = startingBalances,
            dailyDdBasis = haltConfig.dailyDdBasis,
            totalDdBasis = haltConfig.totalDdBasis,
            strategyRiskLimits = perStrategyRisk.mapValues { (_, limits) -> limits.toLimits() },
            bookCapital = bookCapital,
            instruments = instruments,
            accountingConfig = accountingConfig,
            tradedSymbols = symbols,
            barStore = barStore,
            brokerKind = effectiveExecution.brokerKind,
            executionConfig = effectiveExecution,
            bookRiskConfig = bookRiskConfig,
            pacerCooldownDurationMsFor = { id -> perStrategyRisk[id]?.cooldownAfterLossMs },
            pacerCooldownAfterConsecutiveFor = { id ->
                perStrategyRisk[id]?.cooldownAfterLossAfterConsecutive ?: 1
            },
            maxOrderQty = maxOrderQty,
            maxOrderNotional = maxOrderNotional,
            priceCollarFrac = priceCollarFrac,
            forceBars = forceBars,
            barWindows = barWindows,
            binaryBarStore = binaryBarStore,
            tickFills = tickFills,
            gateFor = gateFor,
            preCandle = preCandle,
            regimeWeights = regimeWeights,
            enforceLiveBreakers = enforceLiveBreakers,
            runawayMaxRoundTrips = runawayMaxRoundTrips,
            runawayMaxRejections = runawayMaxRejections,
        )
    }

    /**
     * For a fan-out sweep: a builder of one shared decoded feed plus a per-scenario engine factory.
     * The sweep driver pulls the shared feed once and pushes each tick into every engine via
     * `ReplayEngine.ingest`, so the basket is decoded once per worker instead of once per scenario.
     * The shared feed is built from default params because the market data is independent of the
     * per-scenario knobs (params, strategy variant, broker, instruments, balance); each engine is
     * built with an empty feed (it is driven externally) but its own compiled strategy and isolated
     * broker/P&L/risk state. A scenario may not change the symbol set — it keys the shared feed.
     */
    fun scenarioEngines(): Pair<() -> TickFeed, (ScenarioSpec) -> ReplayEngine> {
        val sharedFeed = { backtest(emptyMap()).detachFeed() }
        val engineFor = { s: ScenarioSpec ->
            scenarioBacktest(s).toEngine(SequenceTickFeed(emptySequence()))
        }
        return sharedFeed to engineFor
    }

    /** One scenario as its own standalone backtest — the per-combo sweep path for [barFills]. */
    fun scenarioBacktest(s: ScenarioSpec): Backtest =
        backtest(
            overrides = s.params,
            ast = s.ast ?: this.ast,
            brokerKind = s.brokerKind ?: this.brokerKind,
            executionConfig = executionConfig,
            instruments = s.instruments ?: this.instruments,
            startingBalance = s.startingBalance ?: this.startingBalance,
        )

    companion object {
        /** User-facing setup error; commands catch it and print `e.message`. */
        class SetupError(
            message: String,
        ) : RuntimeException(message)

        /** Split a qktSymbol into (broker, bare): "MT5:EURUSD" -> ("MT5","EURUSD"), "EURUSD" -> ("BACKTEST","EURUSD"). */
        private fun brokerAndBare(qktSymbol: String): Pair<String, String> {
            val parts = qktSymbol.split(":", limit = 2)
            return if (parts.size == 2) parts[0] to parts[1] else "BACKTEST" to qktSymbol
        }

        private fun hasCompleteFetchedBars(
            barStore: LocalBarStore,
            stream: ProvisionStream,
            window: TimeWindow,
            from: LocalDate,
            to: LocalDate,
        ): Boolean {
            var day = from
            val timeframe = window.canonicalSpec()
            while (!day.isAfter(to)) {
                if (!barStore.hasDay(stream.broker, stream.bareSymbol, timeframe, day)) return false
                day = day.plusDays(1)
            }
            return true
        }

        private data class BarReplayConfig(
            val forceBars: Boolean,
            val tickFills: Boolean,
            val binaryBarStore: BinaryBarStore,
            val finestDeclared: Map<String, TimeWindow>,
            val barWindows: Map<String, TimeWindow>,
        )

        /**
         * Resolve bar-replay parameters for a backtest. Validates --bars/--tick-fills constraints,
         * chooses the coarsest built timeframe that divides each declared timeframe, and checks
         * bar coverage so portfolio and single-strategy backtests fail identically.
         */
        private fun resolveBarReplay(
            args: Args,
            dataRoot: String,
            from: Instant,
            to: Instant,
            symbols: List<String>,
            streams: List<com.qkt.dsl.ast.StreamDecl>,
            candleWindow: TimeWindow?,
            executionConfig: ExecutionSimulationConfig,
        ): BarReplayConfig {
            val forceBars = args.flag("bars")
            val tickFills = args.flag("tick-fills")
            require(!tickFills || forceBars) {
                "--tick-fills requires --bars (bars drive signals; ticks resolve fills)"
            }
            require(!forceBars || tickFills || executionConfig.brokerKind != BrokerKind.MT5_SIM) {
                "--bars with --broker mt5-sim is unsafe: synthetic bar extremes do not preserve " +
                    "MT5 trigger prices or market spread. Use --bars --tick-fills or full tick replay"
            }
            require(!tickFills || executionConfig.latencyMs == 0L) {
                "--tick-fills is not valid with execution latency (${executionConfig.latencyMs}ms): " +
                    "filtered ticks cannot preserve delayed-order release timing; use full tick replay"
            }
            require(
                !tickFills ||
                    streams
                        .map { it.timeframe }
                        .distinct()
                        .size <= 1,
            ) {
                "--tick-fills is not valid for mixed-timeframe strategies: a finer-stream close can place " +
                    "a cross-symbol order after the other symbol's bar was already resolved"
            }
            val binaryBarStore = BinaryBarStore(Paths.get(dataRoot))
            val barTfOverride = args.option("bar-tf")?.let { TimeWindow.parse(it) }
            val finestDeclared: Map<String, TimeWindow> =
                streams
                    .filter { it.qktSymbol in symbols }
                    .groupBy { it.qktSymbol }
                    .mapNotNull { (symbol, symbolStreams) ->
                        symbolStreams
                            .mapNotNull { it.timeframe?.let(TimeWindow::parse) }
                            .minByOrNull { it.durationMs }
                            ?.let { symbol to it }
                    }.toMap()
            val barWindows: Map<String, TimeWindow> =
                if (!forceBars) {
                    finestDeclared
                } else {
                    finestDeclared.mapValues { (sym, declared) ->
                        val (broker, bare) = brokerAndBare(sym)
                        if (barTfOverride != null) {
                            require(declared.durationMs % barTfOverride.durationMs == 0L) {
                                "--bar-tf ${barTfOverride.canonicalSpec()} must divide $sym's " +
                                    "declared ${declared.canonicalSpec()}"
                            }
                            barTfOverride
                        } else {
                            binaryBarStore
                                .builtTimeframes(broker, bare)
                                .filter { declared.durationMs % it.durationMs == 0L }
                                .maxByOrNull { it.durationMs }
                                ?: declared // no usable built tf — let the guardrail below report it
                        }
                    }
                }
            if (forceBars) {
                val fromDay = LocalDate.ofInstant(from, ZoneOffset.UTC)
                val toDay = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
                for (sym in symbols) {
                    val tf = barWindows[sym] ?: candleWindow ?: continue
                    val (broker, bare) = brokerAndBare(sym)
                    val coverage =
                        com.qkt.marketdata.store.BarCompletenessValidator.validate(
                            binaryBarStore,
                            broker,
                            bare,
                            tf,
                            fromDay,
                            toDay,
                            defaultCalendars().calendarFor(bare),
                        )
                    System.err.println(
                        "qkt: bar coverage $sym ${coverage.coveredTradingDays}/${coverage.requestedTradingDays} " +
                            "trading days (${tf.canonicalSpec()})",
                    )
                    if (coverage.missingDays.isNotEmpty()) {
                        val message =
                            "--bars: incomplete built bars for $sym: ${coverage.coveredTradingDays}/" +
                                "${coverage.requestedTradingDays} trading days; missing " +
                                coverage.missingDays.joinToString(",") +
                                ". Run: qkt data build-bars $bare --tf ${tf.canonicalSpec()} " +
                                "--from $fromDay --to ${toDay.plusDays(1)}"
                        if (!args.flag("allow-incomplete")) {
                            throw com.qkt.backtest.IncompleteDataException(
                                "$message\n  re-run with --allow-incomplete to proceed anyway",
                            )
                        }
                        System.err.println("qkt: WARNING — $message")
                    }
                }
                System.err.println("qkt: --bars research tier — bar-approximated intrabar fills; not for grading")
            }
            return BarReplayConfig(
                forceBars = forceBars,
                tickFills = tickFills,
                binaryBarStore = binaryBarStore,
                finestDeclared = finestDeclared,
                barWindows = barWindows,
            )
        }

        fun build(
            args: Args,
            ast: StrategyAst,
            fetcherOverride: DataFetcher? = null,
        ): BacktestContext {
            val from = parseInstant(args.requireOption("from"))
            val to = parseInstant(args.requireOption("to"))
            val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")

            val declaredSymbols = ast.streams.map { it.qktSymbol }.distinct()
            val symbolsOverride =
                args
                    .option("symbols")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
            val symbols =
                if (symbolsOverride != null) {
                    val unknown = symbolsOverride.filter { it !in declaredSymbols }
                    if (unknown.isNotEmpty()) {
                        throw SetupError(
                            "--symbols contains unknown symbols $unknown; strategy declares $declaredSymbols",
                        )
                    }
                    symbolsOverride
                } else {
                    declaredSymbols
                }
            val datasetContext = datasetContext(args, listOf(ast), symbols, from, to)
            // Default to the shared store (~/.qkt/data) so `qkt backtest` reads the same place
            // `qkt fetch` / `qkt data convert` write. A pinned dataset carries its own root unless
            // the caller explicitly overrides it with --data-root for relocated snapshots.
            val dataRoot = args.option("data-root") ?: datasetContext.dataRoot ?: DataRoot.resolve().toString()

            // Legacy `--fetcher dukascopy --fetcher-script <path>` still wins when set.
            val legacyFetcher: DataFetcher? =
                when (val name = args.option("fetcher")) {
                    null -> null
                    "dukascopy" -> ScriptDataFetcher.dukascopy(Paths.get(args.requireOption("fetcher-script")))
                    else -> throw SetupError("unsupported --fetcher '$name' (supported: dukascopy)")
                }
            val noFetch = args.flag("no-fetch")
            val fetcher: DataFetcher? =
                when {
                    noFetch -> null
                    fetcherOverride != null -> fetcherOverride
                    legacyFetcher != null -> legacyFetcher
                    else -> DukascopyTickFetcher()
                }
            val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = fetcher)
            val barStore = LocalBarStore(root = Paths.get(dataRoot))

            val candleWindow =
                ast.streams
                    .firstOrNull()
                    ?.timeframe
                    ?.let { TimeWindow.parse(it) }

            val instrumentsPath: Path =
                args.option("instruments")?.let(Paths::get) ?: Paths.get(dataRoot).resolve("instruments.yaml")
            val instruments: InstrumentRegistry =
                if (Files.exists(instrumentsPath)) {
                    LayeredInstrumentRegistry(
                        listOf(YamlInstrumentRegistry.load(instrumentsPath), StandardInstrumentRegistry),
                    )
                } else {
                    if (args.option("instruments") != null) {
                        throw SetupError("--instruments file not found: $instrumentsPath")
                    }
                    StandardInstrumentRegistry
                }

            val brokerKind =
                when (val raw = args.option("broker")) {
                    null, "paper" -> BrokerKind.PAPER
                    "mt5-sim" -> BrokerKind.MT5_SIM
                    else -> throw SetupError("unknown --broker '$raw' (valid: paper, mt5-sim)")
                }
            // Same config-driven halt/accounting construction the live daemon uses, so a strategy
            // that would halt live halts at the same point in its backtest. The basis balance is
            // the backtest's own starting balance.
            val cfg = Config.load(Config.resolvePath(args.option("config")))
            val executionConfig = executionConfig(args, cfg, brokerKind)
            val accountingConfig = accountingConfig(args, cfg)
            val replaySymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()
            val barReplay =
                resolveBarReplay(
                    args = args,
                    dataRoot = dataRoot,
                    from = from,
                    to = to,
                    symbols = symbols,
                    streams = ast.streams,
                    candleWindow = candleWindow,
                    executionConfig = executionConfig,
                )
            val forceBars = barReplay.forceBars
            val tickFills = barReplay.tickFills
            val binaryBarStore = barReplay.binaryBarStore
            val barWindows = barReplay.barWindows

            val provisioner: () -> Unit = {
                val allProvisionStreams =
                    replaySymbols
                        .map { brokerAndBare(it) }
                        .filter { (broker, _) -> broker != "MACRO" && broker != "BYBIT" }
                        .distinct()
                        .map { (broker, bare) -> ProvisionStream(broker = broker, bareSymbol = bare) }
                val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
                val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
                val tickProvisionStreams =
                    allProvisionStreams.filterNot { stream ->
                        val window = barReplay.finestDeclared["${stream.broker}:${stream.bareSymbol}"]
                        window != null &&
                            hasCompleteFetchedBars(
                                barStore,
                                stream,
                                window,
                                provisionFrom,
                                provisionTo,
                            )
                    }
                val (fetchableStreams, validateOnlyStreams) =
                    tickProvisionStreams.partition { DukascopyInstrument.ofOrNull(it.bareSymbol) != null }
                // --bars replays the pre-built bar store and never reads ticks, so skip tick fetch +
                // completeness validation: it would otherwise scan the tick store and warn on holiday
                // holes the bar run doesn't care about (pure waste + log noise every gate run).
                if (!barReplay.forceBars && !provisionTo.isBefore(provisionFrom) && tickProvisionStreams.isNotEmpty()) {
                    BacktestDataProvisioner(store).ensure(
                        streams = fetchableStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = !noFetch,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { defaultCalendars().calendarFor(it) },
                    )
                    BacktestDataProvisioner(store).ensure(
                        streams = validateOnlyStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = false,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { defaultCalendars().calendarFor(it) },
                    )
                }
                // Macro series (MACRO:) provisioning from FRED. Fetch enough history before the
                // window for the strategy's warmup (90 calendar days ~ 60 business days). Skipped on
                // --no-fetch; hasRange avoids re-fetching a window the store already brackets.
                val macroStreams = ast.streams.filter { it.qktSymbol in symbols && it.broker == "MACRO" }
                if (macroStreams.isNotEmpty() && !noFetch && !provisionTo.isBefore(provisionFrom)) {
                    val macroStore = MacroSeriesStore(Paths.get(dataRoot))
                    val fredFetcher = FredSeriesFetcher(macroStore)
                    val policyRateFetcher = PolicyRateSeriesFetcher(macroStore)
                    val macroFrom = provisionFrom.minusDays(90)
                    for (s in macroStreams) {
                        if (!macroStore.hasRange(s.symbol, macroFrom, provisionTo)) {
                            val policySeries = PolicyRateSeries.fromId(s.symbol)
                            if (policySeries == null) {
                                fredFetcher.fetch(s.symbol, macroFrom, provisionTo)
                            } else {
                                policyRateFetcher.fetch(policySeries, macroFrom, provisionTo)
                            }
                        }
                    }
                }
            }

            val haltConfig =
                HaltConfig(
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                )

            return BacktestContext(
                ast = ast,
                from = from,
                to = to,
                brokerKind = executionConfig.brokerKind,
                executionConfig = executionConfig,
                symbols = symbols,
                store = store,
                instruments = instruments,
                barStore = barStore,
                datasetEvidence = datasetContext.evidence,
                accountingConfig = accountingConfig,
                candleWindow = candleWindow,
                startingBalance = startingBalance,
                haltConfig = haltConfig,
                provisioner = provisioner,
                replaySymbols = replaySymbols,
                bookRiskConfig = cfg.bookRisk,
                perStrategyRisk = cfg.perStrategyRisk,
                maxOrderQty = cfg.maxOrderQty,
                maxOrderNotional = cfg.maxOrderNotional,
                priceCollarFrac = cfg.priceCollarFrac,
                enforceLiveBreakers = args.flag("enforce-live-breakers"),
                runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
                runawayMaxRejections = cfg.runawayMaxRejections,
                forceBars = forceBars,
                barWindows = barWindows,
                binaryBarStore = binaryBarStore,
                tickFills = tickFills,
            )
        }

        /**
         * Backtest a PORTFOLIO file: its children run as N attributed strategies on one engine
         * (strategyId `<portfolio>:<alias>`) sharing one account, with the book-risk layer from
         * config. WHEN..RUN portfolio rules are evaluated by a shared [PortfolioGate] and applied
         * as per-strategy signal gating, matching the live per-child topology.
         */
        fun buildPortfolio(
            args: Args,
            compiled: com.qkt.dsl.portfolio.PortfolioCompiled,
            fetcherOverride: DataFetcher? = null,
        ): BacktestContext {
            val from = parseInstant(args.requireOption("from"))
            val to = parseInstant(args.requireOption("to"))
            val declaredCapital = compiled.ast.capital
            val requestedBalance = args.option("starting-balance")?.let(::BigDecimal)
            if (declaredCapital != null && requestedBalance != null && requestedBalance != declaredCapital) {
                throw SetupError(
                    "--starting-balance $requestedBalance conflicts with portfolio CAPITAL $declaredCapital; " +
                        "the portfolio declaration is authoritative",
                )
            }
            val startingBalance = declaredCapital ?: requestedBalance ?: BigDecimal("10000")
            val allocations = capitalAllocations(compiled.ast)
            val startingBalances =
                compiled.children
                    .mapNotNull { child ->
                        allocations[child.alias]?.let { child.strategyId to it }
                    }.toMap()

            val streams = compiled.children.flatMap { it.ast.streams }
            val symbols = streams.map { it.qktSymbol }.distinct()

            // Build the shared portfolio gate so WHEN..RUN rules suppress child signals in backtest
            // exactly as PortfolioSupervisor does in live. The gate is fed closed candles before
            // strategies evaluate them, so the gate state is current for each bar.
            val portfolioCalendar =
                symbols.firstOrNull()?.let { defaultCalendars().calendarFor(it.substringAfter(':')) }
                    ?: TradingCalendar.crypto()
            val portfolioGate =
                PortfolioGate(
                    ast = compiled.ast,
                    clock = FixedClock(time = from.toEpochMilli()),
                    calendar = portfolioCalendar,
                ).also {
                    it.prepare()
                    it.initialState()
                }
            val gateFor: (String) -> Boolean = { strategyId ->
                portfolioGate.currentState().activeByAlias[strategyId.substringAfter(":")] == true
            }
            val preCandle: (com.qkt.marketdata.Candle) -> Unit = { candle ->
                portfolioGate.onCandle(candle)
            }
            val aliasToStrategyId = compiled.children.associate { it.alias to it.strategyId }
            val regimeWeights: () -> Map<String, BigDecimal> = {
                portfolioGate.currentState().weightByAlias.mapKeys { (alias, _) ->
                    aliasToStrategyId[alias] ?: alias
                }
            }

            val datasetContext =
                datasetContext(
                    args,
                    compiled.children.map { it.ast },
                    symbols,
                    from,
                    to,
                )
            val dataRoot = args.option("data-root") ?: datasetContext.dataRoot ?: DataRoot.resolve().toString()

            val legacyFetcher: DataFetcher? =
                when (val name = args.option("fetcher")) {
                    null -> null
                    "dukascopy" -> ScriptDataFetcher.dukascopy(Paths.get(args.requireOption("fetcher-script")))
                    else -> throw SetupError("unsupported --fetcher '$name' (supported: dukascopy)")
                }
            val noFetch = args.flag("no-fetch")
            val fetcher: DataFetcher? =
                when {
                    noFetch -> null
                    fetcherOverride != null -> fetcherOverride
                    legacyFetcher != null -> legacyFetcher
                    else -> DukascopyTickFetcher()
                }
            val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = fetcher)
            val barStore = LocalBarStore(root = Paths.get(dataRoot))
            val candleWindow = streams.firstOrNull()?.timeframe?.let { TimeWindow.parse(it) }

            val instrumentsPath: Path =
                args.option("instruments")?.let(Paths::get) ?: Paths.get(dataRoot).resolve("instruments.yaml")
            val instruments: InstrumentRegistry =
                if (Files.exists(instrumentsPath)) {
                    LayeredInstrumentRegistry(
                        listOf(YamlInstrumentRegistry.load(instrumentsPath), StandardInstrumentRegistry),
                    )
                } else {
                    if (args.option("instruments") != null) {
                        throw SetupError("--instruments file not found: $instrumentsPath")
                    }
                    StandardInstrumentRegistry
                }

            val brokerKind =
                when (val raw = args.option("broker")) {
                    null, "paper" -> BrokerKind.PAPER
                    "mt5-sim" -> BrokerKind.MT5_SIM
                    else -> throw SetupError("unknown --broker '$raw' (valid: paper, mt5-sim)")
                }
            val cfg = Config.load(Config.resolvePath(args.option("config")))
            val executionConfig = executionConfig(args, cfg, brokerKind)
            val barReplay =
                resolveBarReplay(
                    args = args,
                    dataRoot = dataRoot,
                    from = from,
                    to = to,
                    symbols = symbols,
                    streams = streams,
                    candleWindow = candleWindow,
                    executionConfig = executionConfig,
                )
            val forceBars = barReplay.forceBars
            val tickFills = barReplay.tickFills
            val binaryBarStore = barReplay.binaryBarStore
            val barWindows = barReplay.barWindows
            val accountingConfig = accountingConfig(args, cfg)
            val replaySymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()

            val provisioner: () -> Unit = {
                val allProvisionStreams =
                    replaySymbols
                        .map { brokerAndBare(it) }
                        .filter { (broker, _) -> broker != "MACRO" && broker != "BYBIT" }
                        .distinct()
                        .map { (broker, bare) -> ProvisionStream(broker = broker, bareSymbol = bare) }
                val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
                val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
                val tickProvisionStreams =
                    allProvisionStreams.filterNot { stream ->
                        val window = barReplay.finestDeclared["${stream.broker}:${stream.bareSymbol}"]
                        window != null &&
                            hasCompleteFetchedBars(
                                barStore,
                                stream,
                                window,
                                provisionFrom,
                                provisionTo,
                            )
                    }
                val (fetchableStreams, validateOnlyStreams) =
                    tickProvisionStreams.partition { DukascopyInstrument.ofOrNull(it.bareSymbol) != null }
                if (!barReplay.forceBars && !provisionTo.isBefore(provisionFrom) && tickProvisionStreams.isNotEmpty()) {
                    BacktestDataProvisioner(store).ensure(
                        streams = fetchableStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = !noFetch,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { defaultCalendars().calendarFor(it) },
                    )
                    BacktestDataProvisioner(store).ensure(
                        streams = validateOnlyStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = false,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { defaultCalendars().calendarFor(it) },
                    )
                }
            }

            val haltConfig =
                HaltConfig(
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                )
            val effectiveBookRiskConfig =
                if (compiled.ast.allocate != null && declaredCapital != null) {
                    val base =
                        cfg.bookRisk ?: com.qkt.risk.book
                            .BookRiskConfig()
                    val rebalanceBars =
                        compiled.ast.allocate.rebalanceEveryDurationMs?.let { durationMs ->
                            candleWindow?.let { window ->
                                BigDecimal(durationMs)
                                    .divide(BigDecimal(window.durationMs), 0, java.math.RoundingMode.CEILING)
                                    .toInt()
                            } ?: 0
                        } ?: 0
                    base.copy(
                        capital = base.capital ?: declaredCapital,
                        allocation =
                            com.qkt.risk.book.Allocation(
                                method = com.qkt.risk.book.AllocationMethod.REGIME_WEIGHTED,
                                rebalanceEveryBars = rebalanceBars,
                            ),
                    )
                } else {
                    cfg.bookRisk
                }

            return BacktestContext(
                ast = compiled.children.first().ast,
                from = from,
                to = to,
                brokerKind = executionConfig.brokerKind,
                executionConfig = executionConfig,
                symbols = symbols,
                store = store,
                instruments = instruments,
                barStore = barStore,
                datasetEvidence = datasetContext.evidence,
                accountingConfig = accountingConfig,
                candleWindow = candleWindow,
                startingBalance = startingBalance,
                startingBalances = startingBalances,
                bookCapital = declaredCapital,
                haltConfig = haltConfig,
                provisioner = provisioner,
                replaySymbols = replaySymbols,
                strategiesOverride = {
                    compiled.children.map { child ->
                        child.strategyId to
                            GatedChild(
                                strategyId = child.strategyId,
                                inner = child.compiled,
                                hold = child.hold,
                                gateFor = gateFor,
                                flattenSymbols = child.symbols,
                            )
                    }
                },
                gateFor = gateFor,
                preCandle = preCandle,
                regimeWeights = regimeWeights,
                bookRiskConfig = effectiveBookRiskConfig,
                perStrategyRisk = cfg.perStrategyRisk,
                maxOrderQty = cfg.maxOrderQty,
                maxOrderNotional = cfg.maxOrderNotional,
                priceCollarFrac = cfg.priceCollarFrac,
                enforceLiveBreakers = args.flag("enforce-live-breakers"),
                runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
                runawayMaxRejections = cfg.runawayMaxRejections,
                forceBars = forceBars,
                barWindows = barWindows,
                binaryBarStore = binaryBarStore,
                tickFills = tickFills,
            )
        }

        private fun accountingConfig(
            args: Args,
            cfg: Config,
        ): AccountingConfig {
            val cliSymbols =
                args.options("fx-symbol").associate { token ->
                    val eq = token.indexOf('=')
                    if (eq <= 0 || eq == token.lastIndex) {
                        throw SetupError("bad --fx-symbol '$token'; expected PAIR=QKT_SYMBOL")
                    }
                    token.substring(0, eq).trim() to token.substring(eq + 1).trim()
                }
            return AccountingConfig(
                accountCurrency =
                    AccountCurrency(
                        args.option("account-currency")
                            ?: cfg.accountCurrency,
                    ),
                missingPolicy =
                    FxMissingPolicy.fromConfig(
                        args.option("fx-missing-policy")
                            ?: cfg.accountingConfig.missingPolicy.name
                                .lowercase(),
                    ),
                source =
                    args.option("fx-source")
                        ?: cfg.accountingConfig.source,
                symbols = cfg.accountingConfig.symbols + cliSymbols,
            )
        }

        private fun executionConfig(
            args: Args,
            cfg: Config,
            brokerKind: BrokerKind,
        ): ExecutionSimulationConfig {
            val seed = args.option("seed")?.toLongOrNull() ?: cfg.execution["seed"]?.toLongOrNull()
            if (args.flag("chaos") && args.option("execution") != null) {
                throw SetupError("--chaos cannot be combined with --execution")
            }
            val preset =
                if (args.flag("chaos")) {
                    ExecutionPreset.STRESS
                } else {
                    (args.option("execution") ?: cfg.execution["preset"])
                        ?.let(ExecutionPreset::fromConfig)
                }
            var result =
                if (preset != null) {
                    ExecutionSimulationConfig.defaultsFor(preset, seed)
                } else {
                    ExecutionSimulationConfig.forBrokerKind(brokerKind).copy(seed = seed)
                }
            (args.option("execution-latency") ?: cfg.execution["latency"])?.let {
                result = result.copy(latencyMs = parseLatencyMs(it))
            }
            (args.option("slippage") ?: cfg.execution["slippage"])?.let {
                val (spec, points) = parseSlippage(it)
                result = result.copy(slippage = spec, slippagePoints = points)
            }
            (args.option("reject-every") ?: cfg.execution["reject_every"])?.let {
                result = result.copy(rejectEvery = it.toIntOrNull() ?: throw SetupError("bad reject_every '$it'"))
            }
            (args.option("partial-fill") ?: cfg.execution["partial_fill"])?.let {
                result = result.copy(partialFillFraction = BigDecimal(it))
            }
            return result
        }

        private fun parseLatencyMs(raw: String): Long {
            val trimmed = raw.trim().lowercase().removePrefix("fixed:")
            val millis =
                when {
                    trimmed.endsWith("ms") -> trimmed.removeSuffix("ms")
                    trimmed.endsWith("s") ->
                        return (trimmed.removeSuffix("s").toBigDecimal() * BigDecimal("1000")).toLong()
                    else -> trimmed
                }
            return millis.toLongOrNull() ?: throw SetupError("bad execution latency '$raw'")
        }

        private fun parseSlippage(raw: String): Pair<SlippageSpec, Int> {
            val trimmed = raw.trim().lowercase()
            return when {
                trimmed == "zero" || trimmed == "none" -> SlippageSpec.ZERO to 0
                trimmed == "instrument" || trimmed == "instrument:slippagepoints" -> SlippageSpec.INSTRUMENT to 0
                trimmed.startsWith("fixed-points:") ->
                    SlippageSpec.FIXED_POINTS to parsePoints(raw, trimmed.substringAfter(':'))
                trimmed.startsWith("fixed:") ->
                    SlippageSpec.FIXED_POINTS to parsePoints(raw, trimmed.substringAfter(':'))
                trimmed.startsWith("uniform-random:") ->
                    SlippageSpec.UNIFORM_RANDOM to parsePoints(raw, trimmed.substringAfter(':'))
                trimmed.startsWith("uniform:") ->
                    SlippageSpec.UNIFORM_RANDOM to parsePoints(raw, trimmed.substringAfter(':'))
                else -> throw SetupError("bad slippage '$raw' (valid: zero, instrument, fixed-points:N, uniform:N)")
            }
        }

        private fun parsePoints(
            raw: String,
            points: String,
        ): Int =
            points.toIntOrNull()
                ?: throw SetupError("bad slippage '$raw': points must be an integer")

        private data class DatasetContext(
            val evidence: DatasetEvidence,
            val dataRoot: String?,
        )

        private fun datasetContext(
            args: Args,
            strategyAsts: List<StrategyAst>,
            symbols: List<String>,
            from: Instant,
            to: Instant,
        ): DatasetContext {
            val raw = args.option("dataset") ?: return DatasetContext(mutableDatasetEvidence(args), dataRoot = null)
            val path = Path.of(raw)
            val snapshot =
                try {
                    DatasetSnapshots.read(path)
                } catch (e: Exception) {
                    throw SetupError("cannot read --dataset $path: ${e.message}")
                }
            validateDatasetSnapshot(path, snapshot, symbols, from, to, args.option("data-root")?.let(Path::of))
            validateDatasetFieldRequirements(path, snapshot, strategyAsts)
            return DatasetContext(
                evidence =
                    DatasetEvidence(
                        id = snapshot.id,
                        hash = EvidenceHasher.sha256(path),
                        qualityPolicy = snapshot.qualityPolicy.mode,
                        mutableStore = false,
                    ),
                dataRoot = snapshot.dataRoot,
            )
        }

        private fun validateDatasetFieldRequirements(
            path: Path,
            snapshot: DatasetSnapshot,
            strategyAsts: List<StrategyAst>,
        ) {
            val failures = mutableListOf<String>()
            val totalTicks = snapshot.files.sumOf { it.tickCount }
            val bidAskTicks = snapshot.files.sumOf { it.bidAskTicks }
            val volumeTicks = snapshot.files.sumOf { it.volumeTicks }
            val hasBidAsk = snapshot.files.all { it.tickCount == 0 || it.bidAskTicks == it.tickCount }
            val hasVolume = snapshot.files.all { it.tickCount == 0 || it.volumeTicks == it.tickCount }
            for (ast in strategyAsts) {
                val aliasToSymbol = ast.streams.associate { it.alias to it.symbol }
                val requirements = StrategyDataRequirementScanner.scan(ast)
                val missingQuotes =
                    requirements.quoteAliases
                        .filter { aliasToSymbol[it] == snapshot.symbol }
                        .sorted()
                if (missingQuotes.isNotEmpty() && !hasBidAsk) {
                    failures.add(
                        "strategy reads bid/ask/spread on ${missingQuotes.joinToString()} but --dataset $path " +
                            "has bid/ask on $bidAskTicks/$totalTicks ticks",
                    )
                }
                val missingVolume =
                    requirements.volumeAliases
                        .filter { aliasToSymbol[it] == snapshot.symbol }
                        .sorted()
                if (missingVolume.isNotEmpty() && !hasVolume) {
                    failures.add(
                        "strategy reads volume on ${missingVolume.joinToString()} but --dataset $path " +
                            "has volume on $volumeTicks/$totalTicks ticks",
                    )
                }
            }
            if (failures.isNotEmpty()) {
                throw SetupError("dataset field capability check failed:\n  ${failures.joinToString("\n  ")}")
            }
        }

        private fun validateDatasetSnapshot(
            path: Path,
            snapshot: DatasetSnapshot,
            symbols: List<String>,
            from: Instant,
            to: Instant,
            dataRootOverride: Path?,
        ) {
            val bareSymbols = symbols.map { it.substringAfter(':') }.distinct()
            if (bareSymbols != listOf(snapshot.symbol)) {
                throw SetupError("--dataset $path covers ${snapshot.symbol}, but run symbols are $bareSymbols")
            }
            val runFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
            val runToExclusive = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC).plusDays(1)
            val snapshotFrom = LocalDate.parse(snapshot.from)
            val snapshotTo = LocalDate.parse(snapshot.to)
            if (runFrom.isBefore(snapshotFrom) || runToExclusive.isAfter(snapshotTo)) {
                throw SetupError(
                    "--dataset $path covers $snapshotFrom..$snapshotTo, but run needs $runFrom..$runToExclusive",
                )
            }
            val verification = DatasetSnapshots.verify(snapshot, dataRootOverride = dataRootOverride, strict = true)
            if (!verification.ok) {
                throw SetupError(
                    "dataset snapshot verification failed:\n  ${verification.failures.joinToString("\n  ")}",
                )
            }
        }

        private fun mutableDatasetEvidence(args: Args): DatasetEvidence =
            DatasetEvidence(
                qualityPolicy =
                    if (args.flag("allow-incomplete")) {
                        "allow-incomplete"
                    } else {
                        "default-completeness-check"
                    },
                mutableStore = true,
                warning = "Dataset is a mutable local store, not an immutable snapshot.",
            )

        internal fun defaultCalendars(): SymbolCalendars =
            SymbolCalendars(
                rules =
                    listOf(
                        SymbolCalendars.Rule("BTC*", TradingCalendar.crypto()),
                        SymbolCalendars.Rule("ETH*", TradingCalendar.crypto()),
                        SymbolCalendars.Rule("*USDT", TradingCalendar.crypto()),
                        // US equity index CFDs trade NYSE hours. DXY trades the fx week, so it
                        // falls through to the fxDefault default below.
                        SymbolCalendars.Rule("SPX", TradingCalendar.nyse()),
                        SymbolCalendars.Rule("NDX", TradingCalendar.nyse()),
                        SymbolCalendars.Rule("DJI", TradingCalendar.nyse()),
                        SymbolCalendars.Rule("RUT", TradingCalendar.nyse()),
                    ),
                default = TradingCalendar.fxDefault(),
            )

        fun parseInstant(s: String): Instant =
            if (s.contains('T')) {
                Instant.parse(if (s.endsWith("Z")) s else "${s}Z")
            } else {
                LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
            }
    }
}
