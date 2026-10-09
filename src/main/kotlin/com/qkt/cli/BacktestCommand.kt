package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.instrument.futuresSymbols
import com.qkt.instrument.optionSymbols
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path

/** `qkt backtest <strategy.qkt>` — historical replay producing a backtest report. */
class BacktestCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
    private val runsRootOverride: Path? = null,
) {
    fun run(): Int {
        // Project-local runs first: config defaults layer beneath explicit flags (flag > run >
        // global > built-in). Evidence keeps the true CLI; the merged record lands in the report.
        val configPath = Config.resolvePath(args.option("config"))
        val cfg = Config.load(configPath)
        for (warning in cfg.backtest.warnings) System.err.println("qkt: WARNING — config $warning")
        val resolvedRun =
            try {
                BacktestRunResolution.resolve(args, cfg)
            } catch (e: BacktestContext.Companion.SetupError) {
                // Malformed values (bad --param): one line, same code as before.
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val file = resolvedRun.strategy.toString()
        val path = resolvedRun.strategy
        val effective = resolvedRun.effective
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

        val format: ReportFormat = if (effective.flag("json")) ReportFormat.Json else ReportFormat.Text

        // `--param NAME=VALUE` overrides, config first with explicit CLI entries winning; a
        // comma-list means "sweep this" (resolved in BacktestRunSelection, same errors as before).
        val overrides = resolvedRun.paramOverrides

        val ctx =
            try {
                when (parsedFile) {
                    is com.qkt.dsl.parse.ParsedFile.StrategyFile ->
                        BacktestContext.build(effective, parsedFile.ast, fetcherOverride)
                    is com.qkt.dsl.parse.ParsedFile.PortfolioFile ->
                        BacktestContext.buildPortfolio(
                            effective,
                            com.qkt.dsl.portfolio.PortfolioLoader
                                .load(path),
                            fetcherOverride,
                        )
                }
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: com.qkt.backtest.IncompleteDataException) {
                // --bars coverage is judged while the context is built, not at provision time.
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: IllegalStateException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        val restoreLogs = QuietBacktestLogs.silenceUnless(effective.flag("verbose"), effective.flag("debug"))
        try {
            try {
                ctx.provision()
            } catch (e: com.qkt.backtest.IncompleteDataException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

            return try {
                val result =
                    BacktestEvidence.attach(
                        effective,
                        BacktestMetricsWindows.run(ctx.backtest(overrides), effective, ctx.from, ctx.to),
                        path,
                        parsedFile,
                        ctx.executionEvidence(),
                        ctx.datasetEvidence,
                        resolvedRun.resolved,
                    )
                val runsHome = runsRootOverride ?: BacktestReportSink.defaultHome()
                val reportBase = cfg.reportDir?.let { BacktestEvidence.resolveConfigRelative(cfg, it) }
                BacktestReportSink.resolve(effective, path, runsHome, reportBase)?.let { dir ->
                    BacktestReportSink.write(dir, result)
                    // stderr: stdout stays pure for --json piping while the console shows the path.
                    // Display shortens only the real home: a redirected test root must print literally.
                    System.err.println("Report saved: ${BacktestReportSink.display(dir)}")
                }
                val futures = ctx.instruments.futuresSymbols(ctx.symbols)
                val options = ctx.instruments.optionSymbols(ctx.symbols)
                ReportPrinter.print(
                    result,
                    format,
                    System.out,
                    ctx.brokerKind,
                    futures,
                    options,
                    effective.flag("verbose"),
                )
                printExecutionNotes(ctx.symbols, futures, options, ctx.brokerKind)
                ExitCodes.SUCCESS
            } catch (e: com.qkt.dsl.compile.CompileError) {
                System.err.println("qkt: error: ${e.message}")
                ExitCodes.USER_ERROR
            } catch (e: IllegalStateException) {
                System.err.println("qkt: error: ${e.message}")
                if (effective.flag("debug")) e.printStackTrace(System.err)
                ExitCodes.USER_ERROR
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                if (effective.flag("debug")) e.printStackTrace(System.err)
                ExitCodes.USER_ERROR
            }
        } finally {
            restoreLogs()
        }
    }
}
