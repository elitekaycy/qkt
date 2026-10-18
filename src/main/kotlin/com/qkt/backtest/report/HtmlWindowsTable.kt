package com.qkt.backtest.report

import com.qkt.backtest.WindowReport

/**
 * The report.html section for `--oos-split` / `--metrics-window` windows (#1451): one row per window with
 * the same figures the console prints, so the split is readable without opening result.json.
 */
internal object HtmlWindowsTable {
    /** The section title: the in-sample / out-of-sample pair, or plain windows. */
    fun title(windows: List<WindowReport>): String =
        if (windows.any { it.window.name == "out_of_sample" }) "In-sample and out-of-sample" else "Windows"

    fun render(
        windows: List<WindowReport>,
        currency: String?,
    ): String =
        buildString {
            append("<table><thead><tr><th>window</th><th>dates</th><th>start equity</th><th>end equity</th>")
            append("<th>profit</th><th>round trips</th><th>win rate</th><th>Sharpe</th><th>worst drop</th>")
            append("<th>costs</th></tr></thead><tbody>")
            for (w in windows) {
                val g = w.report
                val pnl = g.totalPnL
                append("<tr><td>${htmlEscape(WindowFacts.label(w))}</td>")
                append("<td>${htmlEscape(WindowFacts.dates(w))}</td>")
                append("<td>${htmlEscape(HumanFormat.money(w.equityStart, currency, signed = false))}</td>")
                append("<td>${htmlEscape(HumanFormat.money(w.equityEnd, currency, signed = false))}</td>")
                append("<td class=\"${if (pnl.signum() >= 0) "pos" else "neg"}\">")
                append(
                    "${htmlEscape(
                        HumanFormat.money(pnl, currency),
                    )} ${htmlEscape(WindowFacts.returnPct(w).trim())}</td>",
                )
                append("<td>${g.roundTrips}</td>")
                append("<td>${htmlEscape(HumanFormat.percent(g.winRate))}</td>")
                append("<td>${htmlEscape(HumanFormat.ratio(g.sharpeRatio))}</td>")
                append("<td>${htmlEscape(HumanFormat.percent(g.maxDrawdown))}</td>")
                append("<td>${htmlEscape(WindowFacts.costs(g, currency))}</td></tr>")
            }
            append("</tbody></table>")
        }
}
