package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.PerformanceReport
import java.math.BigDecimal
import java.math.MathContext

/**
 * The HTML report's headline views of one [BacktestResult]: the plain-language summary strip,
 * headline cards, trade statistics, and the Monte Carlo summary with its fan chart. Every
 * headline number carries a unit and its raw value on hover (`title=`); raw values stay in the
 * CSV/JSON artifacts for tools (#1378, #1379). Drawdowns live in [HtmlDrawdownTable].
 */
internal object HtmlPerformanceTables {
    fun headlineCards(result: BacktestResult): String {
        val r = result.global
        val currency = result.accounting?.accountCurrency

        fun card(
            label: String,
            value: String,
            raw: String,
            classes: String = "",
        ) = "<div class=\"card $classes\"><div class=\"label\">$label</div>" +
            "<div class=\"value\" title=\"${htmlEscape(raw)}\">$value</div></div>"
        return buildString {
            append("<p class=\"verdict\">${htmlEscape(verdictLine(result, currency))}</p>")
            append(
                card(
                    "Total PnL",
                    HumanFormat.money(r.totalPnL, currency),
                    r.totalPnL.toPlainString(),
                    if (r.totalPnL.signum() >= 0) "pos" else "neg",
                ),
            )
            append(card("Fills", r.tradeCount.toString(), "fills, not round trips"))
            append(
                card(
                    "Win rate",
                    HumanFormat.percent(r.winRate),
                    "${r.winRate.toPlainString()} of decided closing fills",
                ),
            )
            append(
                card(
                    "Sharpe",
                    HumanFormat.ratio(r.sharpeRatio),
                    "annualized return per unit of risk; raw ${r.sharpeRatio?.toPlainString() ?: "n/a"}",
                ),
            )
            append(
                card(
                    "Calmar",
                    HumanFormat.ratio(r.calmarRatio),
                    "total return / max drawdown, not annualized; raw ${r.calmarRatio?.toPlainString() ?: "n/a"}",
                ),
            )
            append(
                card(
                    "Max DD",
                    HumanFormat.percent(r.maxDrawdown),
                    r.maxDrawdown.toPlainString(),
                    "neg",
                ),
            )
            append(
                card(
                    "Profit factor",
                    HumanFormat.ratio(r.profitFactor),
                    r.profitFactor?.toPlainString() ?: "n/a",
                ),
            )
        }
    }

    /**
     * One plain-language line a non-qkt reader can quote: how much was made or lost, how much of
     * it is still open, the worst drop, and a low-sample warning for tiny trade counts (#1379).
     */
    private fun verdictLine(
        result: BacktestResult,
        currency: String?,
    ): String {
        val r = result.global
        val start = r.equityCurve.firstOrNull()?.equity
        val end = r.equityCurve.lastOrNull()?.equity
        val open = result.finalPositions.values.count { it.quantity.signum() != 0 }
        val parts = mutableListOf<String>()
        if (start != null && end != null) {
            val ret =
                if (start.signum() > 0) {
                    " (${HumanFormat.percent(r.totalPnL.divide(start, MathContext.DECIMAL64), signed = true)})"
                } else {
                    ""
                }
            parts +=
                "Start ${HumanFormat.money(start, currency, signed = false)} → " +
                "end ${HumanFormat.money(end, currency, signed = false)}$ret"
        }
        val trades = StringBuilder("${r.tradeCount} ${if (r.tradeCount == 1) "trade" else "trades"}")
        if (open > 0) {
            trades.append(", $open still open (${HumanFormat.money(r.unrealizedTotal, currency)} unrealized)")
        }
        parts += trades.toString()
        parts += "worst drop ${HumanFormat.percent(r.maxDrawdown)}"
        var line = parts.joinToString(" · ")
        if (r.tradeCount < 30) line += ". Few trades — treat win rate and ratios with suspicion."
        return line
    }

    /** Drawdowns render in [HtmlDrawdownTable]; kept here so callers have one entry point. */
    fun drawdownTable(periods: List<DrawdownPeriod>): String = HtmlDrawdownTable.render(periods)

    fun tradeStatsTable(
        r: PerformanceReport,
        currency: String?,
    ): String =
        buildString {
            fun moneyRow(
                label: String,
                amount: BigDecimal,
            ) = "<tr><td>$label</td><td title=\"${amount.toPlainString()}\">" +
                "${HumanFormat.money(amount, currency)}</td></tr>"
            append("<table><tbody>")
            append(moneyRow("Average win", r.avgWin))
            append(moneyRow("Average loss", r.avgLoss))
            append(moneyRow("Largest win", r.largestWin))
            append(moneyRow("Largest loss", r.largestLoss))
            append("<tr><td>Max consecutive losses</td><td>${r.maxConsecutiveLosses}</td></tr>")
            append(moneyRow("Commission paid", r.commissionPaid))
            append(moneyRow("Swap paid", r.swapPaid))
            if (r.rollCostsPaid.signum() != 0) {
                append(moneyRow("Roll costs paid", r.rollCostsPaid))
            }
            if (r.fundingPaid.signum() !=
                0
            ) {
                append(moneyRow("Funding paid", r.fundingPaid))
            }
            append("</tbody></table>")
        }

    fun monteCarloSection(
        r: PerformanceReport,
        config: HtmlReportConfig,
        currency: String?,
    ): String {
        val mc =
            r.monteCarlo ?: return "<p>Insufficient trades for Monte Carlo " +
                "(need ${config.minTradesForMonteCarlo}+).</p>"
        return buildString {
            fun moneyRow(
                label: String,
                amount: BigDecimal,
            ) = "<tr><td>$label</td><td title=\"${amount.toPlainString()}\">" +
                "${HumanFormat.money(amount, currency)}</td></tr>"
            append("<table><tbody>")
            append("<tr><td>Simulations</td><td>${mc.simulations}</td></tr>")
            append(moneyRow("P5 final equity", mc.finalEquityP5))
            append(moneyRow("P50 final equity", mc.finalEquityP50))
            append(moneyRow("P95 final equity", mc.finalEquityP95))
            append(
                "<tr><td>P5 max DD</td><td title=\"${mc.maxDrawdownP5.toPlainString()}\">" +
                    "${HumanFormat.percent(mc.maxDrawdownP5)}</td></tr>",
            )
            append(
                "<tr><td>P95 max DD</td><td title=\"${mc.maxDrawdownP95.toPlainString()}\">" +
                    "${HumanFormat.percent(mc.maxDrawdownP95)}</td></tr>",
            )
            append(
                "<tr><td>P(final &lt; 0)</td><td title=\"${mc.probabilityNegativeFinal.toPlainString()}\">" +
                    "${HumanFormat.percent(mc.probabilityNegativeFinal)}</td></tr>",
            )
            append("</tbody></table>")
            append(SvgChart.fanChart(mc.equityFanByTradeIndex, width = 1000, height = 360))
        }
    }
}
