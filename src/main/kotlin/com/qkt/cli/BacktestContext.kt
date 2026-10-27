package com.qkt.cli

import com.qkt.accounting.AccountingConfig
import com.qkt.backtest.Backtest
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.GatedChild
import com.qkt.candles.TimeWindow
import com.qkt.common.SymbolCalendars
import com.qkt.common.TimeRange
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.dsl.portfolio.capitalAllocations
import com.qkt.evidence.DatasetEvidence
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.hub.resolveHubRoot
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.research.ReplayEngine
import java.math.BigDecimal
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
    /** Where `HUB:` streams are served from; resolved once so every run path agrees. */
    val hubStoreRoot: java.nio.file.Path,
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
    private val calendars: CalendarResolver = CalendarResolver(null) { null },
) {
    /** Fetch + completeness-validate the data the run(s) will touch. Throws IncompleteDataException on holes. */
    fun provision() = provisioner()

    /**
     * True when fills resolve on synthesized bar ticks (`--bars` without `--tick-fills`, or a symbol
     * the store has only bars for). Sweeps then run per-combo ([com.qkt.backtest.sweep.BacktestSweep]):
     * bar synthesis orders each bar's extremes adverse-first for the open position, which differs
     * per combo, so one shared tick stream cannot serve them all.
     */
    val barFills: Boolean by lazy { (forceBars && !tickFills) || backtest(emptyMap()).barFills.any }

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
        /** Explicit strategy instances (bot run sessions); null compiles [ast] as usual. */
        strategies: List<Pair<String, com.qkt.strategy.Strategy>>? = null,
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
            strategies
                ?: strategiesOverride?.invoke(overrides)
                ?: listOf(ast.name to AstCompiler().compile(ast, overrides))
        val effectiveExecution =
            if (executionConfig == this.executionConfig && brokerKind != this.brokerKind) {
                // Rebuild the broker-derived preset fields, but carry the run's venue
                // position model: it is an operator/venue property, not a broker-kind
                // property (#1071) — dropping it made a brokerKind override silently
                // grade a different book than the baked context.
                ExecutionSimulationConfig
                    .forBrokerKind(brokerKind)
                    .copy(positionMode = executionConfig.positionMode)
            } else {
                executionConfig
            }
        val calendar = backtestCalendar(symbols, instruments, calendars)
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
            hubStoreRoot = hubStoreRoot,
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

        fun build(
            args: Args,
            ast: StrategyAst,
            fetcherOverride: DataFetcher? = null,
        ): BacktestContext {
            val from = parseInstant(args.requireOption("from"))
            val to = parseInstant(args.requireOption("to"))
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
            val datasetContext = BacktestDatasetEvidence.datasetContext(args, listOf(ast), symbols, from, to)
            // Default to the shared store (~/.qkt/data) so `qkt backtest` reads the same place
            // `qkt fetch` / `qkt data convert` write. A pinned dataset carries its own root unless
            // the caller explicitly overrides it with --data-root for relocated snapshots.
            val dataRoot = args.option("data-root") ?: datasetContext.dataRoot ?: CliDataRoot.of(args).toString()

            val stores = BacktestSetup.stores(args, dataRoot, fetcherOverride)
            val store = stores.ticks
            val barStore = stores.bars

            val candleWindow =
                ast.streams
                    .firstOrNull()
                    ?.timeframe
                    ?.let { TimeWindow.parse(it) }

            val instruments: InstrumentRegistry =
                InstrumentFiles.forBacktest(Paths.get(dataRoot), args, symbols, from, to)

            val brokerKind = BacktestSetup.brokerKind(args)
            // Same config-driven halt/accounting construction the live daemon uses, so a strategy
            // that would halt live halts at the same point in its backtest. The basis balance is
            // the backtest's own starting balance, which defaults to the config's like the daemon's.
            val cfg = Config.load(Config.resolvePath(args.option("config")))
            val startingBalance = backtestStartingBalance(args, cfg)
            val executionConfig = BacktestSimulationOptions.executionConfig(args, cfg, brokerKind)
            val accountingConfig = BacktestSimulationOptions.accountingConfig(args, cfg)
            val replaySymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()
            warnStandaloneBookRiskIgnored(cfg.bookRisk)
            val barReplay =
                BacktestBarReplay.resolveBarReplay(
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

            val calendars = cfg.backtestCalendars(args.option("venue"), instruments)
            val provisioner =
                BacktestSetup.provisioner(
                    args,
                    dataRoot,
                    stores,
                    from to to,
                    replaySymbols to barReplay,
                    ast.streams,
                    instruments,
                    calendars::calendarFor,
                )

            val haltConfig = BacktestSetup.haltConfig(cfg)

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
                hubStoreRoot = resolveHubRoot(args.option("hub-root"), cfg.hub, Path.of(dataRoot)),
                accountingConfig = accountingConfig,
                candleWindow = candleWindow,
                startingBalance = startingBalance,
                haltConfig = haltConfig,
                provisioner = provisioner,
                replaySymbols = replaySymbols,
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
                calendars = calendars,
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

            val datasetContext =
                BacktestDatasetEvidence.datasetContext(
                    args,
                    compiled.children.map { it.ast },
                    symbols,
                    from,
                    to,
                )
            val dataRoot = args.option("data-root") ?: datasetContext.dataRoot ?: CliDataRoot.of(args).toString()

            val stores = BacktestSetup.stores(args, dataRoot, fetcherOverride)
            val store = stores.ticks
            val barStore = stores.bars
            val candleWindow = streams.firstOrNull()?.timeframe?.let { TimeWindow.parse(it) }

            val instruments: InstrumentRegistry =
                InstrumentFiles.forBacktest(Paths.get(dataRoot), args, symbols, from, to)

            val cfg = Config.load(Config.resolvePath(args.option("config")))
            val calendars = cfg.backtestCalendars(args.option("venue"), instruments)
            val wiring = PortfolioBacktestWiring(compiled, from, backtestCalendar(symbols, instruments, calendars))

            val brokerKind = BacktestSetup.brokerKind(args)
            val executionConfig = BacktestSimulationOptions.executionConfig(args, cfg, brokerKind)
            val barReplay =
                BacktestBarReplay.resolveBarReplay(
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
            val accountingConfig = BacktestSimulationOptions.accountingConfig(args, cfg)
            val replaySymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()

            val provisioner =
                BacktestSetup.provisioner(
                    args,
                    dataRoot,
                    stores,
                    from to to,
                    replaySymbols to barReplay,
                    streams,
                    instruments,
                    calendars::calendarFor,
                )

            val haltConfig = BacktestSetup.haltConfig(cfg)
            val effectiveBookRiskConfig =
                PortfolioBacktestWiring.bookRisk(
                    compiled,
                    declaredCapital,
                    cfg.bookRisk,
                    candleWindow,
                )

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
                hubStoreRoot = resolveHubRoot(args.option("hub-root"), cfg.hub, Path.of(dataRoot)),
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
                                gateFor = wiring.gateFor,
                                flattenSymbols = child.symbols,
                            )
                    }
                },
                gateFor = wiring.gateFor,
                preCandle = wiring.preCandle,
                regimeWeights = wiring.regimeWeights,
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
                calendars = calendars,
            )
        }

        /** qkt's [BuiltInCalendars] rules: the calendar a symbol takes when no venue says. */
        internal fun defaultCalendars(): SymbolCalendars = BuiltInCalendars.rules

        fun parseInstant(s: String): Instant =
            if (s.contains('T')) {
                Instant.parse(if (s.endsWith("Z")) s else "${s}Z")
            } else {
                LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
            }
    }
}
