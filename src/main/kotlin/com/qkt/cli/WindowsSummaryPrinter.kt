package com.qkt.cli

import com.qkt.backtest.WindowReport
import com.qkt.backtest.report.HumanFormat
import com.qkt.backtest.report.WindowFacts
import java.io.PrintStream

/**
 * The in-sample / out-of-sample block of the console summary and `--verbose` (#1451): one entry per
 * `--oos-split` or `--metrics-window` window with its equity, profit, round trips, win rate, Sharpe,
 * worst drop and costs. Prints nothing when the run declared no windows.
 */
internal object WindowsSummaryPrinter {
    fun print(
        windows: List<WindowReport>,
        currency: String?,
        out: PrintStream,
    ) {
        if (windows.isEmpty()) return
        out.println()
        out.println(if (windows.any { it.window.name == "out_of_sample" }) "In-sample / out-of-sample" else "Windows")
        val width = windows.maxOf { WindowFacts.label(it).length }.coerceAtLeast(17) + 2
        for (w in windows) {
            val g = w.report
            val equity =
                "${HumanFormat.money(w.equityStart, currency, signed = false)} -> " +
                    HumanFormat.money(w.equityEnd, currency, signed = false)
            out.println("  ${WindowFacts.label(w).padEnd(width)}${WindowFacts.dates(w)}")
            out.println(
                "  ${"".padEnd(width)}$equity   ${HumanFormat.money(g.totalPnL, currency)}${WindowFacts.returnPct(w)}",
            )
            out.println(
                "  ${"".padEnd(width)}${WindowFacts.roundTrips(g)}, Sharpe ${HumanFormat.ratio(g.sharpeRatio)}, " +
                    "worst drop ${HumanFormat.percent(g.maxDrawdown)}",
            )
            out.println("  ${"".padEnd(width)}costs: ${WindowFacts.costs(g, currency)}")
        }
    }
}
