package com.qkt.cli

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestDataProvisioner
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ProvisionStream
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.ScriptDataFetcher
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher
import com.qkt.marketdata.store.macro.FredSeriesFetcher
import com.qkt.marketdata.store.macro.MacroSeriesStore
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
    val symbols: List<String>,
    val store: DefaultDataStore,
    val instruments: InstrumentRegistry,
    val barStore: LocalBarStore,
    private val candleWindow: TimeWindow?,
    private val startingBalance: BigDecimal,
    private val haltConfig: HaltConfig,
    private val provisioner: () -> Unit,
    private val strategiesOverride: ((Map<String, String>) -> List<Pair<String, com.qkt.strategy.Strategy>>)? = null,
    private val bookRiskConfig: com.qkt.risk.book.BookRiskConfig? = null,
) {
    /** Fetch + completeness-validate the data the run(s) will touch. Throws IncompleteDataException on holes. */
    fun provision() = provisioner()

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
            request = MarketRequest(symbols = symbols, from = range.from, to = range.to),
            candleWindow = candleWindow,
            startingBalance = startingBalance,
            instruments = instruments,
            barStore = barStore,
            brokerKind = brokerKind,
            bookRiskConfig = bookRiskConfig,
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
            backtest(
                overrides = s.params,
                ast = s.ast ?: this.ast,
                brokerKind = s.brokerKind ?: this.brokerKind,
                instruments = s.instruments ?: this.instruments,
                startingBalance = s.startingBalance ?: this.startingBalance,
            ).toEngine(SequenceTickFeed(emptySequence()))
        }
        return sharedFeed to engineFor
    }

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
            // Default to the shared store (~/.qkt/data) so `qkt backtest` reads the same place
            // `qkt fetch` / `qkt data convert` write — otherwise the tick store and the bar store
            // (which already resolves via DataRoot) would diverge and cached ticks would be missed.
            val dataRoot = args.option("data-root") ?: DataRoot.resolve().toString()
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

            val provisioner: () -> Unit = {
                val provisionStreams =
                    ast.streams
                        .filter { it.qktSymbol in symbols && DukascopyInstrument.ofOrNull(it.symbol) != null }
                        .map { ProvisionStream(broker = it.broker, bareSymbol = it.symbol) }
                val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
                val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
                if (!provisionTo.isBefore(provisionFrom) && provisionStreams.isNotEmpty()) {
                    BacktestDataProvisioner(store).ensure(
                        streams = provisionStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = !noFetch,
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
                    val macroFrom = provisionFrom.minusDays(90)
                    for (s in macroStreams) {
                        if (!macroStore.hasRange(s.symbol, macroFrom, provisionTo)) {
                            fredFetcher.fetch(s.symbol, macroFrom, provisionTo)
                        }
                    }
                }
            }

            // Same config-driven halt construction the live daemon uses, so a strategy
            // that would halt live halts at the same point in its backtest. The basis
            // balance is the backtest's own starting balance.
            val cfg =
                Config.load(
                    Paths.get(args.option("config") ?: "./qkt.config.yaml"),
                )
            val haltConfig =
                HaltConfig(
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                )

            return BacktestContext(
                ast = ast,
                from = from,
                to = to,
                brokerKind = brokerKind,
                symbols = symbols,
                store = store,
                instruments = instruments,
                barStore = LocalBarStore(root = DataRoot.forDataRoot(args.option("data-root"))),
                candleWindow = candleWindow,
                startingBalance = startingBalance,
                haltConfig = haltConfig,
                provisioner = provisioner,
                bookRiskConfig = cfg.bookRisk,
            )
        }

        /**
         * Backtest a PORTFOLIO file: its children run as N attributed strategies on one engine
         * (strategyId `<portfolio>:<alias>`) sharing one account, with the book-risk layer from
         * config. Regime WHEN..RUN gates are not yet applied in backtest — children run always-on.
         */
        fun buildPortfolio(
            args: Args,
            compiled: com.qkt.dsl.portfolio.PortfolioCompiled,
            fetcherOverride: DataFetcher? = null,
        ): BacktestContext {
            val from = parseInstant(args.requireOption("from"))
            val to = parseInstant(args.requireOption("to"))
            val dataRoot = args.option("data-root") ?: DataRoot.resolve().toString()
            val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")

            val streams = compiled.children.flatMap { it.ast.streams }
            val symbols = streams.map { it.qktSymbol }.distinct()

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

            val provisioner: () -> Unit = {
                val provisionStreams =
                    streams
                        .filter { it.qktSymbol in symbols && DukascopyInstrument.ofOrNull(it.symbol) != null }
                        .map { ProvisionStream(broker = it.broker, bareSymbol = it.symbol) }
                val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
                val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
                if (!provisionTo.isBefore(provisionFrom) && provisionStreams.isNotEmpty()) {
                    BacktestDataProvisioner(store).ensure(
                        streams = provisionStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = !noFetch,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { defaultCalendars().calendarFor(it) },
                    )
                }
            }

            val cfg = Config.load(Paths.get(args.option("config") ?: "./qkt.config.yaml"))
            val haltConfig =
                HaltConfig(
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                )

            return BacktestContext(
                ast = compiled.children.first().ast,
                from = from,
                to = to,
                brokerKind = brokerKind,
                symbols = symbols,
                store = store,
                instruments = instruments,
                barStore = LocalBarStore(root = DataRoot.forDataRoot(args.option("data-root"))),
                candleWindow = candleWindow,
                startingBalance = startingBalance,
                haltConfig = haltConfig,
                provisioner = provisioner,
                strategiesOverride = { compiled.children.map { it.strategyId to it.compiled } },
                bookRiskConfig = cfg.bookRisk,
            )
        }

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
