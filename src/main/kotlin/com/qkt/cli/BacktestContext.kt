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
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.ScriptDataFetcher
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher
import com.qkt.marketdata.store.macro.FredSeriesFetcher
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
    private val haltRules: List<com.qkt.risk.HaltRule>,
    private val provisioner: () -> Unit,
) {
    /** Fetch + completeness-validate the data the run(s) will touch. Throws IncompleteDataException on holes. */
    fun provision() = provisioner()

    /** Build a backtest for [overrides] over [range] (defaults to the full configured window). */
    fun backtest(
        overrides: Map<String, String>,
        range: TimeRange = TimeRange(from, to),
    ): Backtest {
        val strategy = AstCompiler().compile(ast, overrides)
        // The symbol's LIVE calendar, not hardwired crypto: session/range indicators
        // (PreviousDayHigh, session gates) disagree by construction otherwise. The
        // pipeline takes one calendar — resolved from the first symbol; mixed-class
        // baskets keep that limitation (divergence catalog row A9).
        val calendar =
            symbols.firstOrNull()?.let { defaultCalendars().calendarFor(it.substringAfter(':')) }
                ?: com.qkt.common.TradingCalendar
                    .crypto()
        return Backtest.fromStore(
            strategies = listOf(ast.name to strategy),
            haltRules = haltRules,
            calendar = calendar,
            store = store,
            request = MarketRequest(symbols = symbols, from = range.from, to = range.to),
            candleWindow = candleWindow,
            startingBalance = startingBalance,
            instruments = instruments,
            barStore = barStore,
            brokerKind = brokerKind,
        )
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
            val dataRoot = args.option("data-root") ?: "./data"
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
            val haltRules =
                com.qkt.risk.HaltRules.standard(
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    startingBalance = startingBalance,
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
                haltRules = haltRules,
                provisioner = provisioner,
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
