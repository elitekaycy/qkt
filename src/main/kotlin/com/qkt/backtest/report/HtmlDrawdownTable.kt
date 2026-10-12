package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod

/**
 * The "Biggest losing streaks (drawdowns)" table of the HTML report (#1378): deepest first, UTC
 * dates and day/hour durations instead of epoch-ms and millisecond counts, raw values on hover,
 * and everything beyond the top 10 collapsed so a long tail stops dominating the report.
 */
internal object HtmlDrawdownTable {
    fun render(periods: List<DrawdownPeriod>): String {
        if (periods.isEmpty()) return "<p>No drawdowns above threshold.</p>"
        val top = periods.sortedBy { it.depthPct }.take(10)
        return buildString {
            append(tableHtml(top))
            if (periods.size > 10) {
                append("<details><summary>${periods.size - 10} smaller drawdowns</summary>")
                append(tableHtml(periods.sortedBy { it.depthPct }.drop(10)))
                append("</details>")
            }
        }
    }

    private fun tableHtml(periods: List<DrawdownPeriod>): String =
        buildString {
            append("<table><thead><tr>")
            append("<th>Peak</th><th>Trough</th><th>Recovery</th><th>Depth</th>")
            append("<th>Duration</th><th>Status</th></tr></thead><tbody>")
            for (p in periods) {
                append("<tr><td>${HumanFormat.utcDate(p.peakTimestamp)}</td>")
                append("<td>${HumanFormat.utcDate(p.troughTimestamp)}</td>")
                append("<td>${p.recoveryTimestamp?.let { HumanFormat.utcDate(it) } ?: "ongoing"}</td>")
                append("<td title=\"${p.depthPct.toPlainString()}\">${HumanFormat.percent(p.depthPct)}</td>")
                append("<td title=\"${p.durationMs} ms\">${HumanFormat.duration(p.durationMs)}</td>")
                append("<td>${if (p.ongoing) "ongoing" else "recovered"}</td></tr>")
            }
            append("</tbody></table>")
        }
}
