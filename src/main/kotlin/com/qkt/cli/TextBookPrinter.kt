package com.qkt.cli

import com.qkt.backtest.BacktestResult
import java.io.PrintStream

/**
 * The portfolio blocks of the plaintext backtest report: per-strategy attribution, cross-strategy
 * book analytics and book risk. Each is skipped on a single-strategy run.
 */
internal object TextBookPrinter {
    /** Book-risk summary (exposure peaks + book vol) for a portfolio run. Skipped when absent. */
    fun printBookRisk(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val br = r.bookRisk ?: return
        out.println()
        out.println("Book risk")
        out.println("  book vol (annual):  ${br.bookVol?.toPlainString() ?: "n/a"}")
        out.println("  max gross exposure: ${br.maxGrossExposure.toPlainString()}")
        out.println("  max net exposure:   ${br.maxNetExposure.toPlainString()}")
        if (br.events.isNotEmpty()) out.println("  events:             ${br.events.size}")
    }

    /**
     * Cross-strategy relationships for a portfolio backtest: each strategy's share of book return and
     * of book risk, plus pairwise return correlation. Skipped on single-strategy runs (no book).
     */
    fun printBookAnalytics(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val ba = r.bookAnalytics ?: return
        out.println()
        out.println("Book analytics")
        out.println("  contribution to return:")
        for ((id, v) in ba.contributionToReturn.entries.sortedBy { it.key }) {
            out.println("    ${id.padEnd(20)} ${v.toPlainString()}")
        }
        out.println("  risk contribution (PCTR):")
        for ((id, v) in ba.riskContribution.entries.sortedBy { it.key }) {
            out.println("    ${id.padEnd(20)} ${v.toPlainString()}")
        }
        if (ba.returnCorrelation.isNotEmpty()) {
            out.println("  return correlation:")
            for (p in ba.returnCorrelation) {
                out.println("    ${p.a} ~ ${p.b}: ${p.correlation.toPlainString()}")
            }
        }
    }

    /**
     * One line per child strategy of a portfolio backtest — the attribution the global block can't
     * show. Skipped on a single-strategy run, where the global block already says everything.
     */
    fun printPerStrategy(
        r: BacktestResult,
        out: PrintStream,
    ) {
        if (r.perStrategy.size < 2) return
        out.println()
        out.println("Per-strategy")
        for ((id, s) in r.perStrategy.entries.sortedBy { it.key }) {
            out.println(
                "  ${id.padEnd(20)} " +
                    "PnL ${s.totalPnL.toPlainString().padStart(12)}  " +
                    "trades ${s.tradeCount.toString().padStart(5)}  " +
                    "Sharpe ${(s.sharpeRatio?.toPlainString() ?: "n/a").padStart(7)}  " +
                    "Sortino ${(s.sortinoRatio?.toPlainString() ?: "n/a").padStart(7)}  " +
                    "MaxDD ${s.maxDrawdown.toPlainString().padStart(7)}  " +
                    "win ${s.winRate.toPlainString()}",
            )
        }
    }
}
