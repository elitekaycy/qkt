package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path

/** `qkt backtest <strategy.qkt>` — historical replay producing a backtest report. */
class BacktestCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val parsedFile =
            when (val parsed = Dsl.parseFileAny(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    System.err.println("${parsed.errors.size} error${if (parsed.errors.size != 1) "s" else ""}")
                    return ExitCodes.USER_ERROR
                }
            }

        val format: ReportFormat = if (args.flag("json")) ReportFormat.Json else ReportFormat.Text

        // `--param NAME=VALUE` overrides one PARAM/LET per run. A comma-list means "sweep this" —
        // point the user at `qkt sweep` rather than silently picking one value.
        val overrides = mutableMapOf<String, String>()
        for (tok in args.options("param")) {
            val eq = tok.indexOf('=')
            if (eq <= 0) {
                System.err.println("qkt: error: bad --param '$tok'; expected NAME=VALUE")
                return ExitCodes.USER_ERROR
            }
            val name = tok.substring(0, eq).trim()
            val value = tok.substring(eq + 1).trim()
            if (value.contains(',')) {
                System.err.println("qkt: error: multiple values for '$name'; use 'qkt sweep' to grid-search")
                return ExitCodes.USER_ERROR
            }
            overrides[name] = value
        }

        val ctx =
            try {
                when (parsedFile) {
                    is com.qkt.dsl.parse.ParsedFile.StrategyFile ->
                        BacktestContext.build(args, parsedFile.ast, fetcherOverride)
                    is com.qkt.dsl.parse.ParsedFile.PortfolioFile ->
                        BacktestContext.buildPortfolio(
                            args,
                            com.qkt.dsl.portfolio.PortfolioLoader
                                .load(path),
                            fetcherOverride,
                        )
                }
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: IllegalStateException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        return try {
            val result = ctx.backtest(overrides).run()
            ReportPrinter.print(result, format, System.out, ctx.brokerKind)
            if (ctx.brokerKind == BrokerKind.PAPER) {
                System.err.println(
                    "qkt: note: paper broker fills at mid with no spread/slippage — results are optimistic. " +
                        "Use --broker mt5-sim and set commissionPerLot + slippagePoints in instruments.yaml " +
                        "for cost-realistic backtests.",
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
}
