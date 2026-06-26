package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.evidence.AccountingEvidence
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.EvidenceHasher
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
            val result =
                attachEvidence(
                    ctx.backtest(overrides).run(),
                    path,
                    parsedFile,
                    ctx.executionConfig,
                    ctx.datasetEvidence,
                )
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

    private fun attachEvidence(
        result: BacktestResult,
        path: Path,
        parsedFile: ParsedFile,
        executionConfig: com.qkt.backtest.ExecutionSimulationConfig,
        datasetEvidence: DatasetEvidence,
    ): BacktestResult =
        result.copy(
            evidence =
                EvidenceEnvelope(
                    qktVersion = BuildInfo.VERSION,
                    gitSha = BuildInfo.GIT_SHA,
                    buildTimestamp = BuildInfo.BUILD_TIMESTAMP,
                    command = args.tokens,
                    strategyHash = EvidenceHasher.sha256(path),
                    importedFileHashes = importedHashes(path, parsedFile),
                    configHash = configHash(),
                    dataset = datasetEvidence,
                    execution = executionConfig.toEvidence(),
                    accounting = accountingEvidence(result.accounting),
                ),
        )

    private fun accountingEvidence(snapshot: com.qkt.accounting.AccountingSnapshot?): AccountingEvidence? {
        if (snapshot == null) return null
        return AccountingEvidence(
            accountCurrency = snapshot.accountCurrency,
            missingPolicy = snapshot.missingPolicy,
            source = snapshot.source,
            configuredFxSymbols = snapshot.configuredSymbols,
            conversions =
                snapshot.conversions.associate { fx ->
                    "${fx.from}->${fx.to}@${fx.source}" to
                        "rate=${fx.rate.toPlainString()} timestamp=${fx.timestamp}"
                },
            costKinds = snapshot.supportedCostKinds,
            warnings = snapshot.warnings,
        )
    }

    private fun importedHashes(
        path: Path,
        parsedFile: ParsedFile,
    ): Map<String, String> =
        when (parsedFile) {
            is ParsedFile.StrategyFile -> emptyMap()
            is ParsedFile.PortfolioFile -> {
                val parent = path.toAbsolutePath().normalize().parent ?: Path.of(".").toAbsolutePath().normalize()
                parsedFile.ast.imports.associate { imp ->
                    imp.alias to EvidenceHasher.sha256(parent.resolve(imp.path).toAbsolutePath().normalize())
                }
            }
        }

    private fun configHash(): String? {
        val path = Path.of(args.option("config") ?: "./qkt.config.yaml")
        return if (Files.exists(path)) EvidenceHasher.sha256(path) else null
    }
}
