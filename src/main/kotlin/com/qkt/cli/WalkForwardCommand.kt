package com.qkt.cli

import com.qkt.backtest.walkforward.WalkForwardHarness
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** `qkt walkforward <file> --from --to --train 90d --test 30d --step 30d --param NAME=v1,v2 [--rank]`. */
class WalkForwardCommand(
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
        val train = parseDuration(args.requireOption("train")) ?: return badDuration("train")
        val test = parseDuration(args.requireOption("test")) ?: return badDuration("test")
        val step = parseDuration(args.requireOption("step")) ?: return badDuration("step")
        val parallelism = args.option("parallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val topN = args.option("topN")?.toIntOrNull()?.coerceAtLeast(1) ?: 3

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

        val result =
            try {
                WalkForwardHarness(
                    configs = combos.map { it.label to it },
                    backtestFactory = { _, combo, range -> ctx.backtest(combo.overrides, range) },
                    totalRange = TimeRange(ctx.from, ctx.to),
                    trainSize = train,
                    testSize = test,
                    stepSize = step,
                    scoreOf = { rank.score(it) },
                    parallelism = parallelism,
                    topN = topN,
                ).run()
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        println(
            "folds: ${result.folds.size}   mean IS ${rank.flag}: ${result.meanTrainScore.toPlainString()}   " +
                "mean OOS ${rank.flag}: ${result.meanTestScore.toPlainString()}",
        )
        if (result.winnerCounts.isNotEmpty()) {
            println(
                "winner stability: " +
                    result.winnerCounts.entries.sortedByDescending { it.value }.joinToString(
                        ", ",
                    ) { "${it.key}×${it.value}" },
            )
        }
        result.folds.forEachIndexed { i, f ->
            val oos = rank.valueOf(f.testResult.global)?.toPlainString() ?: "—"
            println(
                "fold ${i + 1}: " +
                    "train ${f.trainRange.from}..${f.trainRange.to}  " +
                    "test ${f.testRange.from}..${f.testRange.to}  " +
                    "winner ${f.winnerLabel}  IS ${f.trainScore.toPlainString()}  OOS $oos",
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun badDuration(name: String): Int {
        System.err.println("qkt: error: --$name must be a duration like 90d, 12h, 30m")
        return ExitCodes.USER_ERROR
    }

    private fun parseDuration(spec: String): Duration? =
        runCatching { Duration.ofMillis(TimeWindow.parse(spec).durationMs) }.getOrNull()
}
