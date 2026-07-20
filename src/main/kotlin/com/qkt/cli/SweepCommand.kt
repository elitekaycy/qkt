package com.qkt.cli

import com.qkt.backtest.TradeRecord
import com.qkt.backtest.report.ReportSerializer
import com.qkt.backtest.sweep.SweepReplay
import com.qkt.backtest.sweep.SweepRun
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.evidence.DatasetEvidence
import com.qkt.marketdata.store.DataFetcher
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/** `qkt sweep <file> --from --to --param NAME=v1,v2 [--rank sharpe] [--parallelism N] [--json]`. */
class SweepCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
    fun run(): Int {
        if (args.flag("tick-fills")) {
            System.err.println(
                "qkt: error: --tick-fills is not supported by sweep fan-out; " +
                    "run a single backtest until resolved-feed fan-out is wired",
            )
            return ExitCodes.USER_ERROR
        }
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
                    return ExitCodes.USER_ERROR
                }
            }

        val rank: RankMetric
        val axes: Map<String, List<String>>
        val combos: List<ParamGrid.Combo>
        try {
            rank = RankMetric.fromFlag(args.option("rank"))
            axes = ParamGrid.parseAxes(args.options("param"))
            combos = ParamGrid.expand(axes)
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }
        val parallelism = args.option("parallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        val ctx =
            try {
                BacktestContext.build(args, ast, fetcherOverride)
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        val (sharedFeed, engineFor) = ctx.scenarioEngines()
        val scenarios: List<ScenarioSpec> =
            try {
                args.option("scenarios")?.let { ScenarioFile.load(Path.of(it)) }
                    ?: combos.map { ScenarioSpec(label = it.label, params = it.overrides) }
            } catch (e: IllegalStateException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        System.err.println("qkt: sweeping ${scenarios.size} scenario(s), ranked by ${rank.flag}")

        val ranked: List<SweepRun<ScenarioSpec>> =
            try {
                SweepReplay(
                    configs = scenarios.map { it.label to it },
                    sharedFeed = sharedFeed,
                    engineFor = { _, s -> engineFor(s) },
                    parallelism = parallelism,
                ).run().rankedBy { rank.score(it) }
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        val largeSearchThreshold =
            args.option("large-search-threshold")?.toIntOrNull()
                ?: ResearchGovernance.DEFAULT_LARGE_SEARCH_THRESHOLD
        val unstableWarning =
            if (args.option("scenarios") == null) {
                val gridRuns =
                    ranked.map { run ->
                        SweepRun(
                            label = run.label,
                            config = ParamGrid.Combo(run.label, run.config.params),
                            result = run.result,
                        )
                    }
                ResearchGovernance.unstableNeighborhoodWarning(
                    ranked = gridRuns,
                    axes = axes,
                    rank = rank,
                )
            } else {
                null
            }
        val unstableWarnings = if (unstableWarning != null) listOf(unstableWarning) else emptyList()
        val warnings =
            ResearchGovernance.largeSearchWarnings(
                trialCount = scenarios.size,
                threshold = largeSearchThreshold,
            ) + unstableWarnings

        if (args.flag("json")) {
            printJson(ranked, rank, ctx.datasetEvidence, warnings)
        } else {
            printTable(ranked, rank, warnings)
        }
        return ExitCodes.SUCCESS
    }

    private fun printTable(
        ranked: List<SweepRun<ScenarioSpec>>,
        rank: RankMetric,
        warnings: List<String>,
    ) {
        println(
            "trials: ${ranked.size}   selected metric: ${rank.flag}   " +
                "provenance: sweep.rank(desc)",
        )
        for (warning in warnings) println("warning: $warning")
        println("rank  ${rank.flag.padEnd(12)} trades  totalPnL      sharpe    calmar    maxDD      winRate   label")
        ranked.forEachIndexed { i, run ->
            val r = run.result.global
            println(
                "%-5d %-12s %-7d %-13s %-9s %-9s %-10s %-9s %s".format(
                    i + 1,
                    rank.valueOf(r)?.toPlainString() ?: "—",
                    r.tradeCount,
                    r.totalPnL.toPlainString(),
                    r.sharpeRatio?.toPlainString() ?: "—",
                    r.calmarRatio?.toPlainString() ?: "—",
                    r.maxDrawdown.toPlainString(),
                    r.winRate.toPlainString(),
                    run.label,
                ),
            )
        }
    }

    private fun printJson(
        ranked: List<SweepRun<ScenarioSpec>>,
        rank: RankMetric,
        dataset: DatasetEvidence,
        warnings: List<String>,
    ) {
        val datasetJson = CliEvidenceJson.pinnedDataset(dataset)
        val provenanceJson = ResearchGovernance.metricProvenanceJson("sweep", rank, ranked.size)
        val warningsJson = ResearchGovernance.warningListJson(warnings)
        val rows =
            ranked.joinToString(",") { run ->
                val r = run.result.global
                val params =
                    run.config.params.entries
                        .joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                val daily =
                    r.dailyPnL.entries
                        .sortedBy { it.key }
                        .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" }
                val fillCosts = fillCostSummaryJson(run.result.trades)
                val datasetField = datasetJson?.let { ""","dataset":$it""" } ?: ""
                """{"label":"${run.label}","params":{$params},"rank":"${rank.flag}",""" +
                    """"trialCount":${ranked.size},"metricProvenance":$provenanceJson,""" +
                    """"selectionWarnings":$warningsJson,""" +
                    """"trades":${r.tradeCount},"totalPnL":${r.totalPnL.toPlainString()},""" +
                    """"sharpe":${r.sharpeRatio?.toPlainString() ?: "null"},""" +
                    """"calmar":${r.calmarRatio?.toPlainString() ?: "null"},""" +
                    """"maxDrawdown":${r.maxDrawdown.toPlainString()},"winRate":${r.winRate.toPlainString()},""" +
                    """"maxDailyDrawdown":${r.maxDailyDrawdown.toPlainString()},"dailyPnL":{$daily},""" +
                    """"fillCostSummary":$fillCosts$datasetField}"""
            }
        println("[$rows]")
    }

    private data class FillCostKey(
        val day: String,
        val symbol: String,
    )

    private data class FillCostTotal(
        var fills: Int = 0,
        var lotsAbs: BigDecimal = BigDecimal.ZERO,
        var notionalAbs: BigDecimal = BigDecimal.ZERO,
    )

    /**
     * Aggregate the linear inputs needed to restore paper-fill spread, commission, and slippage
     * without emitting an unbounded fill tape. Grouping by UTC day preserves daily Sharpe and
     * drawdown reconstruction; grouping by symbol preserves each instrument's cost model.
     */
    private fun fillCostSummaryJson(trades: List<TradeRecord>): String {
        val totals = mutableMapOf<FillCostKey, FillCostTotal>()
        for (record in trades) {
            val trade = record.trade
            val day =
                Instant
                    .ofEpochMilli(trade.timestamp)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString()
            val key = FillCostKey(day, trade.symbol)
            val quantityAbs = trade.quantity.abs()
            val contractSize = record.contractSize ?: BigDecimal.ONE
            val total = totals.getOrPut(key) { FillCostTotal() }
            total.fills += 1
            total.lotsAbs = total.lotsAbs.add(quantityAbs)
            total.notionalAbs =
                total.notionalAbs.add(
                    trade.price
                        .abs()
                        .multiply(quantityAbs)
                        .multiply(contractSize),
                )
        }
        return totals.entries
            .sortedWith(compareBy({ it.key.day }, { it.key.symbol }))
            .joinToString(prefix = "[", postfix = "]", separator = ",") { (key, total) ->
                """{"day":${ReportSerializer.jsonString(key.day)},""" +
                    """"symbol":${ReportSerializer.jsonString(key.symbol)},""" +
                    """"fills":${total.fills},"lotsAbs":${total.lotsAbs.toPlainString()},""" +
                    """"notionalAbs":${total.notionalAbs.toPlainString()}}"""
            }
    }
}
