package com.qkt.cli

import com.qkt.backtest.walkforward.WalkForwardHarness
import com.qkt.backtest.walkforward.WalkForwardResult
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.math.BigDecimal
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

        // Means recomputed from each fold's true metric, skipping folds where the metric is
        // undefined. The harness's mean folds in the ranking sentinel (-1E18), which would
        // poison it; here one uncomputable fold simply doesn't count.
        val meanIs = meanOf(result.folds.mapNotNull { rank.defined(it.trainScore) })
        val meanOos = meanOf(result.folds.mapNotNull { rank.valueOf(it.testResult.global) })
        if (args.flag("json")) printJson(result, rank, meanIs, meanOos) else printText(result, rank, meanIs, meanOos)
        return ExitCodes.SUCCESS
    }

    /** Mean of the defined scores, or null when no fold produced one. */
    private fun meanOf(values: List<BigDecimal>): BigDecimal? {
        if (values.isEmpty()) return null
        return values
            .reduce(BigDecimal::add)
            .divide(BigDecimal(values.size), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }

    private fun printText(
        result: WalkForwardResult<*>,
        rank: RankMetric,
        meanIs: BigDecimal?,
        meanOos: BigDecimal?,
    ) {
        println(
            "folds: ${result.folds.size}   mean IS ${rank.flag}: ${meanIs?.toPlainString() ?: "n/a"}   " +
                "mean OOS ${rank.flag}: ${meanOos?.toPlainString() ?: "n/a"}",
        )
        if (result.winnerCounts.isNotEmpty()) {
            println(
                "winner stability: " +
                    result.winnerCounts.entries.sortedByDescending { it.value }.joinToString(", ") {
                        "${it.key}×${it.value}"
                    },
            )
        }
        result.folds.forEachIndexed { i, f ->
            val isScore = rank.defined(f.trainScore)?.toPlainString() ?: "n/a"
            val oos = rank.valueOf(f.testResult.global)?.toPlainString() ?: "n/a"
            println(
                "fold ${i + 1}: " +
                    "train ${f.trainRange.from}..${f.trainRange.to}  " +
                    "test ${f.testRange.from}..${f.testRange.to}  " +
                    "winner ${f.winnerLabel}  IS $isScore  OOS $oos",
            )
        }
    }

    private fun printJson(
        result: WalkForwardResult<*>,
        rank: RankMetric,
        meanIs: BigDecimal?,
        meanOos: BigDecimal?,
    ) {
        fun num(v: BigDecimal?): String = v?.toPlainString() ?: "null"

        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val stability = result.winnerCounts.entries.joinToString(",") { "\"${esc(it.key)}\":${it.value}" }
        val folds =
            result.folds.joinToString(",") { f ->
                """{"train":"${f.trainRange.from}..${f.trainRange.to}",""" +
                    """"test":"${f.testRange.from}..${f.testRange.to}",""" +
                    """"winner":"${esc(f.winnerLabel)}",""" +
                    """"inSample":${num(rank.defined(f.trainScore))},""" +
                    """"outOfSample":${num(rank.valueOf(f.testResult.global))}}"""
            }
        println(
            """{"rank":"${rank.flag}","folds":${result.folds.size},""" +
                """"meanInSample":${num(meanIs)},"meanOutOfSample":${num(meanOos)},""" +
                """"winnerStability":{$stability},"foldDetail":[$folds]}""",
        )
    }

    private fun badDuration(name: String): Int {
        System.err.println("qkt: error: --$name must be a duration like 90d, 12h, 30m")
        return ExitCodes.USER_ERROR
    }

    private fun parseDuration(spec: String): Duration? =
        runCatching { Duration.ofMillis(TimeWindow.parse(spec).durationMs) }.getOrNull()
}
