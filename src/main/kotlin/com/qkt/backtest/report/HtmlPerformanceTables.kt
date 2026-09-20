package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.PerformanceReport

/**
 * The HTML report's views of one [PerformanceReport]: headline cards, drawdown periods, trade
 * statistics, and the Monte Carlo summary with its fan chart.
 */
internal object HtmlPerformanceTables {
    fun headlineCards(r: PerformanceReport): String {
        fun card(
            label: String,
            value: String,
            classes: String = "",
        ) = "<div class=\"card $classes\"><div class=\"label\">$label</div>" +
            "<div class=\"value\">$value</div></div>"
        return buildString {
            append(
                card(
                    "Total PnL",
                    r.totalPnL.toPlainString(),
                    if (r.totalPnL.signum() >= 0) "pos" else "neg",
                ),
            )
            append(card("Trades", r.tradeCount.toString()))
            append(card("Win rate", r.winRate.toPlainString()))
            append(card("Sharpe", r.sharpeRatio?.toPlainString() ?: "n/a"))
            append(card("Calmar", r.calmarRatio?.toPlainString() ?: "n/a"))
            append(card("Max DD", r.maxDrawdown.toPlainString(), "neg"))
            append(card("Profit factor", r.profitFactor?.toPlainString() ?: "n/a"))
        }
    }

    fun drawdownTable(periods: List<DrawdownPeriod>): String {
        if (periods.isEmpty()) return "<p>No drawdowns above threshold.</p>"
        return buildString {
            append("<table><thead><tr>")
            append("<th>Peak</th><th>Trough</th><th>Recovery</th><th>Depth</th>")
            append("<th>Duration ms</th><th>Status</th></tr></thead><tbody>")
            for (p in periods) {
                append("<tr><td>${p.peakTimestamp}</td><td>${p.troughTimestamp}</td>")
                append("<td>${p.recoveryTimestamp ?: "ongoing"}</td>")
                append("<td>${p.depthPct.toPlainString()}</td>")
                append("<td>${p.durationMs}</td>")
                append("<td>${if (p.ongoing) "ongoing" else "recovered"}</td></tr>")
            }
            append("</tbody></table>")
        }
    }

    fun tradeStatsTable(r: PerformanceReport): String =
        buildString {
            append("<table><tbody>")
            append("<tr><td>Average win</td><td>${r.avgWin.toPlainString()}</td></tr>")
            append("<tr><td>Average loss</td><td>${r.avgLoss.toPlainString()}</td></tr>")
            append("<tr><td>Largest win</td><td>${r.largestWin.toPlainString()}</td></tr>")
            append("<tr><td>Largest loss</td><td>${r.largestLoss.toPlainString()}</td></tr>")
            append("<tr><td>Max consecutive losses</td><td>${r.maxConsecutiveLosses}</td></tr>")
            append("<tr><td>Commission paid</td><td>${r.commissionPaid.toPlainString()}</td></tr>")
            append("<tr><td>Swap paid</td><td>${r.swapPaid.toPlainString()}</td></tr>")
            append("</tbody></table>")
        }

    fun monteCarloSection(
        r: PerformanceReport,
        config: HtmlReportConfig,
    ): String {
        val mc =
            r.monteCarlo ?: return "<p>Insufficient trades for Monte Carlo " +
                "(need ${config.minTradesForMonteCarlo}+).</p>"
        return buildString {
            append("<table><tbody>")
            append("<tr><td>Simulations</td><td>${mc.simulations}</td></tr>")
            append("<tr><td>P5 final equity</td><td>${mc.finalEquityP5.toPlainString()}</td></tr>")
            append("<tr><td>P50 final equity</td><td>${mc.finalEquityP50.toPlainString()}</td></tr>")
            append("<tr><td>P95 final equity</td><td>${mc.finalEquityP95.toPlainString()}</td></tr>")
            append("<tr><td>P5 max DD</td><td>${mc.maxDrawdownP5.toPlainString()}</td></tr>")
            append("<tr><td>P95 max DD</td><td>${mc.maxDrawdownP95.toPlainString()}</td></tr>")
            append("<tr><td>P(final &lt; 0)</td><td>${mc.probabilityNegativeFinal.toPlainString()}</td></tr>")
            append("</tbody></table>")
            append(SvgChart.fanChart(mc.equityFanByTradeIndex, width = 1000, height = 360))
        }
    }
}
