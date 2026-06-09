package com.qkt.cli

import com.qkt.backtest.Backtest
import com.qkt.backtest.BrokerKind
import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.ScriptDataFetcher
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** `qkt backtest <strategy.qkt>` — historical replay producing a backtest report. */
class BacktestCommand(
    private val args: Args,
    private val fetcherOverride: com.qkt.marketdata.store.DataFetcher? = null,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val ast =
            when (val parsed = Dsl.parseFile(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    System.err.println("${parsed.errors.size} error${if (parsed.errors.size != 1) "s" else ""}")
                    return ExitCodes.USER_ERROR
                }
            }

        val from = parseInstant(args.requireOption("from"))
        val to = parseInstant(args.requireOption("to"))
        val dataRoot = args.option("data-root") ?: "./data"
        val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")
        val symbolsOverride =
            args
                .option("symbols")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        val format: ReportFormat = if (args.flag("json")) ReportFormat.Json else ReportFormat.Text

        val declaredSymbols = ast.streams.map { it.qktSymbol }.distinct()
        val symbols =
            if (symbolsOverride != null) {
                val unknown = symbolsOverride.filter { it !in declaredSymbols }
                if (unknown.isNotEmpty()) {
                    System.err.println(
                        "qkt: error: --symbols contains unknown symbols $unknown; " +
                            "strategy declares $declaredSymbols",
                    )
                    return ExitCodes.USER_ERROR
                }
                symbolsOverride
            } else {
                declaredSymbols
            }

        val fetcher =
            when (val name = args.option("fetcher")) {
                null -> null
                "dukascopy" -> ScriptDataFetcher.dukascopy(Paths.get(args.requireOption("fetcher-script")))
                else -> {
                    System.err.println("qkt: error: unsupported --fetcher '$name' (supported: dukascopy)")
                    return ExitCodes.USER_ERROR
                }
            }

        val candleWindow: TimeWindow? =
            ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }

        val strategies = listOf(ast.name to AstCompiler().compile(ast))
        // Default to dukascopy auto-fetch so a backtest acquires its own data with no broker
        // running. `--no-fetch` works offline against the cache; the legacy `--fetcher` script
        // path still wins when set; tests inject via fetcherOverride.
        val noFetch = args.flag("no-fetch")
        val storeFetcher: com.qkt.marketdata.store.DataFetcher? =
            when {
                noFetch -> null
                fetcherOverride != null -> fetcherOverride
                fetcher != null -> fetcher
                else ->
                    com.qkt.marketdata.store.dukascopy
                        .DukascopyTickFetcher()
            }
        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = storeFetcher)
        val request = MarketRequest(symbols = symbols, from = from, to = to)

        // Instrument metadata (contract size, volume steps, commission) so SIZING RISK and fill PnL
        // are in real dollars. Built-in StandardInstrumentRegistry covers the FX/metals a backtest
        // can auto-fetch, so the common case needs no setup. A user instruments.yaml layers ahead of
        // it and overrides — that's where you set commission or add a non-standard symbol. Default
        // path follows the data root; --instruments overrides the path.
        val instrumentsPath: Path =
            args
                .option("instruments")
                ?.let(Paths::get)
                ?: Paths.get(dataRoot).resolve("instruments.yaml")
        val standard = com.qkt.instrument.StandardInstrumentRegistry
        val instruments: com.qkt.instrument.InstrumentRegistry =
            if (Files.exists(instrumentsPath)) {
                com.qkt.instrument.LayeredInstrumentRegistry(
                    listOf(
                        com.qkt.instrument.YamlInstrumentRegistry
                            .load(instrumentsPath),
                        standard,
                    ),
                )
            } else {
                if (args.option("instruments") != null) {
                    System.err.println("qkt: error: --instruments file not found: $instrumentsPath")
                    return ExitCodes.USER_ERROR
                }
                standard
            }

        val brokerKind =
            when (val raw = args.option("broker")) {
                null, "paper" -> BrokerKind.PAPER
                "mt5-sim" -> BrokerKind.MT5_SIM
                else -> {
                    System.err.println("qkt: error: unknown --broker '$raw' (valid: paper, mt5-sim)")
                    return ExitCodes.USER_ERROR
                }
            }

        // Seamless data (#337): auto-fetch the days the replay will touch and refuse to run on
        // holes. Provisions bare symbols (the tick store's keying) over [from, to)'s covered days.
        // Only dukascopy-servable symbols (FX/metals) are provisioned; anything else (e.g. crypto
        // bars from `qkt fetch`) falls through to the existing data path untouched.
        val provisionStreams =
            ast.streams
                .filter {
                    it.qktSymbol in symbols &&
                        com.qkt.marketdata.store.dukascopy.DukascopyInstrument
                            .ofOrNull(it.symbol) != null
                }.map { com.qkt.backtest.ProvisionStream(broker = it.broker, bareSymbol = it.symbol) }
        val calendars =
            com.qkt.broker.mt5.SymbolCalendars(
                rules =
                    listOf(
                        com.qkt.broker.mt5.SymbolCalendars
                            .Rule(
                                "BTC*",
                                com.qkt.common.TradingCalendar
                                    .crypto(),
                            ),
                        com.qkt.broker.mt5.SymbolCalendars
                            .Rule(
                                "ETH*",
                                com.qkt.common.TradingCalendar
                                    .crypto(),
                            ),
                        com.qkt.broker.mt5.SymbolCalendars
                            .Rule(
                                "*USDT",
                                com.qkt.common.TradingCalendar
                                    .crypto(),
                            ),
                    ),
                default =
                    com.qkt.common.TradingCalendar
                        .fxDefault(),
            )
        val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
        val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
        if (!provisionTo.isBefore(provisionFrom)) {
            try {
                com.qkt.backtest
                    .BacktestDataProvisioner(store)
                    .ensure(
                        streams = provisionStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = !noFetch,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { calendars.calendarFor(it) },
                    )
            } catch (e: com.qkt.backtest.IncompleteDataException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        }

        return try {
            val backtest =
                Backtest.fromStore(
                    strategies = strategies,
                    store = store,
                    request = request,
                    candleWindow = candleWindow,
                    startingBalance = startingBalance,
                    instruments = instruments,
                    barStore =
                        com.qkt.marketdata.store
                            .LocalBarStore(root = DataRoot.forDataRoot(args.option("data-root"))),
                    brokerKind = brokerKind,
                )
            val result = backtest.run()
            ReportPrinter.print(result, format, System.out, brokerKind)
            if (brokerKind == BrokerKind.PAPER) {
                System.err.println(
                    "qkt: note: paper broker fills at mid with no spread/slippage — results are optimistic. " +
                        "Use --broker mt5-sim and set commissionPerLot in instruments.yaml for cost-realistic backtests.",
                )
            }
            ExitCodes.SUCCESS
        } catch (e: IllegalStateException) {
            System.err.println("qkt: error: ${e.message}")
            if (args.flag("debug")) e.printStackTrace(System.err)
            ExitCodes.USER_ERROR
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: error: ${e.message}")
            if (args.flag("debug")) e.printStackTrace(System.err)
            ExitCodes.USER_ERROR
        }
    }

    private fun parseInstant(s: String): Instant =
        if (s.contains('T')) {
            Instant.parse(if (s.endsWith("Z")) s else "${s}Z")
        } else {
            LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
}
