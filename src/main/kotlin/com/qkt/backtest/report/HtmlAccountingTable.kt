package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot
import com.qkt.backtest.ExecutionFacts
import com.qkt.backtest.PerformanceReport

/**
 * The "Costs and adjustments" table of the HTML report: how the run's fills were priced ([facts], the same
 * lines the console prints, #1437), currency, FX policy, and only the cost kinds this run actually incurred,
 * with amounts (#1378).
 */
internal object HtmlAccountingTable {
    fun render(
        snapshot: AccountingSnapshot,
        global: PerformanceReport,
        facts: ExecutionFacts? = null,
    ): String =
        buildString {
            append("<table><tbody>")
            facts?.let { f ->
                for ((label, text) in ExecutionFactsText.lines(f)) {
                    append("<tr><td>${htmlEscape(label.lowercase())}</td><td>${htmlEscape(text)}</td></tr>")
                }
                ExecutionFactsText.spread(f)?.let { append("<tr><td>spread</td><td>${htmlEscape(it)}</td></tr>") }
            }
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
                val spread = facts?.let(ExecutionFactsText::spread)
                append("<tr><td>costs incurred</td><td>${if (spread == null) "none" else "spread only"}</td></tr>")
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
