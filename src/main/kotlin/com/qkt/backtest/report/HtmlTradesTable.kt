package com.qkt.backtest.report

import com.qkt.backtest.TradeRecord

/**
 * The "Trades" table of the HTML report: the first and last trades per [HtmlReportConfig], with a
 * pointer to `trades.csv` when rows were elided.
 */
internal object HtmlTradesTable {
    fun render(
        trades: List<TradeRecord>,
        config: HtmlReportConfig,
    ): String {
        val sample =
            if (trades.size <= config.tradeTableHead + config.tradeTableTail) {
                trades
            } else {
                trades.take(config.tradeTableHead) + trades.takeLast(config.tradeTableTail)
            }
        return buildString {
            append("<table><thead><tr>")
            append("<th>Timestamp</th><th>Strategy</th><th>Symbol</th><th>Fill side</th>")
            append("<th>Position effect</th><th>Order type</th>")
            append("<th>Qty</th><th>Price</th><th>riskUsd</th><th>Stop</th><th>Target</th><th>Net Account</th>")
            append("<th>Gross Account</th><th>Native</th><th>FX</th><th>Pos Before</th><th>Pos After</th>")
            append("<th>Strat Before</th><th>Strat After</th><th>Contract</th><th>Notional</th>")
            append("</tr></thead><tbody>")
            for (r in sample) {
                val fillNotional = TradeAuditSummaries.fillNotional(r)
                append("<tr>")
                append("<td>${r.trade.timestamp}</td>")
                append("<td>${htmlEscape(r.strategyId)}</td>")
                append("<td>${htmlEscape(r.trade.symbol)}</td>")
                append("<td>${htmlEscape(r.trade.side.name)}</td>")
                append("<td>${htmlEscape(TradeAuditSummaries.positionEffect(r))}</td>")
                append("<td>${htmlEscape(r.orderType ?: "unknown")}</td>")
                append("<td>${r.trade.quantity.toPlainString()}</td>")
                append("<td>${r.trade.price.toPlainString()}</td>")
                append("<td>${r.riskUsd?.toPlainString() ?: "n/a"}</td>")
                append("<td>${r.stopLossPrice?.toPlainString() ?: "n/a"}</td>")
                append("<td>${r.takeProfitPrice?.toPlainString() ?: "n/a"}</td>")
                append("<td>${r.realized.toPlainString()}</td>")
                append(
                    "<td>${r.accountRealized?.toPlainString() ?: "n/a"}" +
                        "${r.accountCurrency?.let { " ${htmlEscape(it)}" } ?: ""}</td>",
                )
                append(
                    "<td>${r.nativeRealized?.toPlainString() ?: "n/a"}" +
                        "${r.nativeCurrency?.let { " ${htmlEscape(it)}" } ?: ""}</td>",
                )
                append("<td>${r.fxRate?.toPlainString() ?: htmlEscape("identity")}</td>")
                append(
                    "<td>${r.accountPositionBefore?.quantity?.toPlainString() ?: "n/a"} " +
                        "@${r.accountPositionBefore?.avgEntryPrice?.toPlainString() ?: "n/a"}</td>",
                )
                append(
                    "<td>${r.accountPositionAfter?.quantity?.toPlainString() ?: "n/a"} " +
                        "@${r.accountPositionAfter?.avgEntryPrice?.toPlainString() ?: "n/a"}</td>",
                )
                append(
                    "<td>${r.strategyPositionBefore?.quantity?.toPlainString() ?: "n/a"} " +
                        "@${r.strategyPositionBefore?.avgEntryPrice?.toPlainString() ?: "n/a"}</td>",
                )
                append(
                    "<td>${r.strategyPositionAfter?.quantity?.toPlainString() ?: "n/a"} " +
                        "@${r.strategyPositionAfter?.avgEntryPrice?.toPlainString() ?: "n/a"}</td>",
                )
                append("<td>${r.contractSize?.toPlainString() ?: "1"}</td>")
                append("<td>${fillNotional.toPlainString()}</td>")
                append("</tr>")
            }
            append("</tbody></table>")
            if (sample.size < trades.size) {
                append("<p>Showing first ${config.tradeTableHead} and last ${config.tradeTableTail} ")
                append("of ${trades.size} trades. Full list in trades.csv.</p>")
            }
        }
    }
}
