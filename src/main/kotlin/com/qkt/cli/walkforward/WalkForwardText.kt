package com.qkt.cli.walkforward

import com.qkt.backtest.walkforward.WalkForwardResult
import com.qkt.cli.RankMetric
import java.math.BigDecimal

/** Prints the walk-forward summary and one line per fold with its winner and IS/OOS scores. */
internal fun printWalkForwardText(
    result: WalkForwardResult<*>,
    rank: RankMetric,
    meanIs: BigDecimal?,
    meanOos: BigDecimal?,
    trialCount: Int,
    warnings: List<String>,
) {
    println(
        "trials: $trialCount   selected metric: ${rank.flag}   " +
            "provenance: walkforward.fold-rank(desc)",
    )
    for (warning in warnings) println("warning: $warning")
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
