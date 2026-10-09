package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot
import com.qkt.backtest.PerformanceReport

/** The "Costs and adjustments" table of the HTML report: currency, FX policy, and only the cost kinds this run actually incurred, with amounts (#1378). */
internal object HtmlAccountingTable {
    fun render(
        snapshot: AccountingSnapshot,
        global: PerformanceReport,
    ): String =
        buildString {
            append("<table><tbody>")
            append("<tr><td>account currency</td><td>${htmlEscape(snapshot.accountCurrency)}</td></tr>")
            append("<tr><td>FX missing policy</td><td>${htmlEscape(snapshot.missingPolicy)}</td></tr>")
            append("<tr><td>FX source</td><td>${htmlEscape(snapshot.source)}</td></tr>")
            if (snapshot.configuredSymbols.isNotEmpty()) {
                append(
                    "<tr><td>configured FX symbols</td><td>${htmlEscape(
                        snapshot.configuredSymbols.toString(),
                    )}</td></tr>",
                )
            }
            if (snapshot.conversions.isNotEmpty()) {
                append("<tr><td>observed conversions</td><td>")
                append(
                    htmlEscape(
                        snapshot.conversions.joinToString("; ") {
                            "${it.from}->${it.to} rate=${it.rate.toPlainString()} timestamp=${it.timestamp} source=${it.source}"
                        },
                    ),
                )
                append("</td></tr>")
            }
            if (snapshot.warnings.isNotEmpty()) {
                append("<tr><td>warnings</td><td>${htmlEscape(snapshot.warnings.joinToString("; "))}</td></tr>")
            }
            val incurred =
                listOf(
                    "commission" to global.commissionPaid,
                    "swap" to global.swapPaid,
                    "roll costs" to global.rollCostsPaid,
                    "funding" to global.fundingPaid,
                ).filter { it.second.signum() != 0 }
            if (incurred.isEmpty()) {
                append("<tr><td>costs incurred</td><td>none</td></tr>")
            } else {
                for ((name, amount) in incurred) {
                    append(
                        "<tr><td>$name</td><td title=\"${amount.toPlainString()}\">" +
                            "${HumanFormat.money(amount, snapshot.accountCurrency)}</td></tr>",
                    )
                }
            }
            append("</tbody></table>")
        }
}
