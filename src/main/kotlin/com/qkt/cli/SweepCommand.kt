package com.qkt.cli

import com.qkt.backtest.sweep.MultiStrategySweep
import com.qkt.backtest.sweep.SweepReplay
import com.qkt.backtest.sweep.SweepRun
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
                    return ExitCodes.USER_ERROR
                }
            }

        val rank: RankMetric
        val combos: List<ParamGrid.Combo>
        try {
            rank = RankMetric.fromFlag(args.option("rank"))
            combos = ParamGrid.parse(args.options("param"))
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

        System.err.println("qkt: sweeping ${combos.size} parameter combination(s), ranked by ${rank.flag}")

        val ranked: List<SweepRun<ParamGrid.Combo>> =
            try {
                val swept =
                    if (ctx.hasAccountHalts) {
                        // Account halts read account-wide state, so combos sharing one account would
                        // halt each other. Fan out per-combo: each engine keeps its own isolated
                        // account/halt state; only the tick decode is shared.
                        val (sharedFeed, engineFor) = ctx.sweepEngines()
                        SweepReplay(
                            configs = combos.map { it.label to it },
                            sharedFeed = sharedFeed,
                            engineFor = { _, combo -> engineFor(combo.overrides) },
                            parallelism = parallelism,
                        ).run()
                    } else {
                        // No account coupling: run the whole grid as one multi-strategy backtest so the
                        // decode + candle aggregation + price tracking run once for all combos.
                        MultiStrategySweep(
                            combos = combos.map { it.label to it },
                            overridesOf = { it.overrides },
                            backtestFor = { labeled -> ctx.multiStrategyBacktest(labeled) },
                            parallelism = parallelism,
                        ).run()
                    }
                swept.rankedBy { rank.score(it) }
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        if (args.flag("json")) printJson(ranked, rank) else printTable(ranked, rank)
        return ExitCodes.SUCCESS
    }

    private fun printTable(
        ranked: List<SweepRun<ParamGrid.Combo>>,
        rank: RankMetric,
    ) {
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
        ranked: List<SweepRun<ParamGrid.Combo>>,
        rank: RankMetric,
    ) {
        val rows =
            ranked.joinToString(",") { run ->
                val r = run.result.global
                val params =
                    run.config.overrides.entries
                        .joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                """{"label":"${run.label}","params":{$params},"rank":"${rank.flag}",""" +
                    """"trades":${r.tradeCount},"totalPnL":${r.totalPnL.toPlainString()},""" +
                    """"sharpe":${r.sharpeRatio?.toPlainString() ?: "null"},""" +
                    """"calmar":${r.calmarRatio?.toPlainString() ?: "null"},""" +
                    """"maxDrawdown":${r.maxDrawdown.toPlainString()},"winRate":${r.winRate.toPlainString()}}"""
            }
        println("[$rows]")
    }
}
