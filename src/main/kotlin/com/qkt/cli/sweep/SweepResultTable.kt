package com.qkt.cli.sweep

import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.RankMetric
import com.qkt.cli.ScenarioSpec

/** Prints the ranked sweep as a fixed-width table, headed by the trial count and selection warnings. */
internal fun printSweepTable(
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
