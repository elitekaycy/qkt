package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot

/** The "Accounting" table of the HTML report: account currency, FX policy and conversions, cost kinds. */
internal object HtmlAccountingTable {
    fun render(snapshot: AccountingSnapshot): String =
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
            append("<tr><td>cost kinds</td><td>${htmlEscape(snapshot.supportedCostKinds.joinToString(", "))}</td></tr>")
            append("</tbody></table>")
        }
}
