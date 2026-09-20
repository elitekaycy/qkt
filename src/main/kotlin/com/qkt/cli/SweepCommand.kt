package com.qkt.cli

import com.qkt.backtest.IncompleteDataException
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.backtest.sweep.SweepReplay
import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.sweep.printSweepJson
import com.qkt.cli.sweep.printSweepTable
import com.qkt.cli.sweep.sweepWarnings
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path

/** `qkt sweep <file> --from --to --param NAME=v1,v2 [--rank sharpe] [--parallelism N] [--json]`. */
class SweepCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
    /** Run every scenario, rank the results and print them; returns a process exit code. */
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
        } catch (e: IncompleteDataException) {
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
                // Plain-bars fills synthesize each bar's extremes adverse-first for the open
                // position, which differs per combo — a shared tick stream cannot serve all
                // engines, so bars sweeps run per-combo (bar decode is cheap and page-cached).
                if (ctx.barFills) {
                    BacktestSweep(
                        configs = scenarios.map { it.label to it },
                        backtestFactory = { _, s -> ctx.scenarioBacktest(s) },
                        parallelism = parallelism,
                    ).run().rankedBy { rank.score(it) }
                } else {
                    SweepReplay(
                        configs = scenarios.map { it.label to it },
                        sharedFeed = sharedFeed,
                        engineFor = { _, s -> engineFor(s) },
                        parallelism = parallelism,
                    ).run().rankedBy { rank.score(it) }
                }
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        val warnings = sweepWarnings(args, ranked, axes, rank, scenarios.size)

        if (args.flag("json")) {
            printSweepJson(ranked, rank, ctx.datasetEvidence, warnings)
        } else {
            printSweepTable(ranked, rank, warnings)
        }
        return ExitCodes.SUCCESS
    }
}
