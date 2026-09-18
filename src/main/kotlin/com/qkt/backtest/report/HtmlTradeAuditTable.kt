package com.qkt.backtest.report

/** The "Trade audit" table of the HTML report: fill counts by side and position effect, PnL split, risk and notional. */
internal object HtmlTradeAuditTable {
    fun render(summary: TradeAuditSummary): String =
        buildString {
            append("<table><tbody>")
            append("<tr><td>Fills</td><td>${summary.fills}</td></tr>")
            append("<tr><td>Buy fills</td><td>${summary.buyFills}</td></tr>")
            append("<tr><td>Sell fills</td><td>${summary.sellFills}</td></tr>")
            append("<tr><td>Side attribution</td><td>${htmlEscape(summary.sideAttribution)}</td></tr>")
            append("<tr><td>Long entry fills</td><td>${summary.longEntryFills}</td></tr>")
            append("<tr><td>Short entry fills</td><td>${summary.shortEntryFills}</td></tr>")
            append("<tr><td>Long exit fills</td><td>${summary.longExitFills}</td></tr>")
            append("<tr><td>Short exit fills</td><td>${summary.shortExitFills}</td></tr>")
            append("<tr><td>Unknown position effect</td><td>${summary.unknownPositionFills}</td></tr>")
            append("<tr><td>Position attribution</td><td>${htmlEscape(summary.positionAttribution)}</td></tr>")
            append("<tr><td>Buy realized</td><td>${summary.buyRealized.toPlainString()}</td></tr>")
            append("<tr><td>Sell realized</td><td>${summary.sellRealized.toPlainString()}</td></tr>")
            append("<tr><td>Gross profit</td><td>${summary.grossProfit.toPlainString()}</td></tr>")
            append("<tr><td>Gross loss</td><td>${summary.grossLoss.toPlainString()}</td></tr>")
            append("<tr><td>Rejections</td><td>${summary.rejections}</td></tr>")
            append("<tr><td>Rejection rate</td><td>${summary.rejectionRate?.toPlainString() ?: "n/a"}</td></tr>")
            append("<tr><td>Risk-audited fills</td><td>${summary.riskAuditedFills}</td></tr>")
            append("<tr><td>Min risk USD</td><td>${summary.minRiskUsd?.toPlainString() ?: "n/a"}</td></tr>")
            append("<tr><td>Avg risk USD</td><td>${summary.avgRiskUsd?.toPlainString() ?: "n/a"}</td></tr>")
            append("<tr><td>Max risk USD</td><td>${summary.maxRiskUsd?.toPlainString() ?: "n/a"}</td></tr>")
            append("<tr><td>Traded notional</td><td>${summary.tradedNotional.toPlainString()}</td></tr>")
            append("<tr><td>Max fill notional</td><td>${summary.maxFillNotional?.toPlainString() ?: "n/a"}</td></tr>")
            append("</tbody></table>")
        }
}
