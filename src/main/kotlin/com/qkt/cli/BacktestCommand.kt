package com.qkt.cli

import com.qkt.backtest.Backtest
import com.qkt.backtest.BrokerKind
import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketRequest
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
        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = fetcher)
        val request = MarketRequest(symbols = symbols, from = from, to = to)

        // Phase 30: load instruments.yaml so SIZING RISK and PaperBroker fill PnL see
        // real contract sizes. Default path follows the data root; --instruments overrides.
        // Backwards-compat: when the file is absent and no flag is set, use Noop so
        // strategies that don't depend on contract-size math keep working.
        val instrumentsPath: Path =
            args
                .option("instruments")
                ?.let(Paths::get)
                ?: Paths.get(dataRoot).resolve("instruments.yaml")
        val instruments: com.qkt.instrument.InstrumentRegistry =
            if (Files.exists(instrumentsPath)) {
                com.qkt.instrument.YamlInstrumentRegistry
                    .load(instrumentsPath)
            } else {
                if (args.option("instruments") != null) {
                    System.err.println("qkt: error: --instruments file not found: $instrumentsPath")
                    return ExitCodes.USER_ERROR
                }
                com.qkt.instrument.NoopInstrumentRegistry
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
                            .LocalBarStore(),
                    brokerKind = brokerKind,
                )
            val result = backtest.run()
            ReportPrinter.print(result, format, System.out)
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
