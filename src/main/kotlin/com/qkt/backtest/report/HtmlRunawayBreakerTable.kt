package com.qkt.backtest.report

import com.qkt.backtest.RunawayBreakerReport
import java.time.Instant

/**
 * The "Runaway breaker" section of the HTML report: the breaker limits, plus a live-behavior
 * warning when an observe-only replay would have tripped it.
 */
internal object HtmlRunawayBreakerTable {
    fun render(report: RunawayBreakerReport): String =
        buildString {
            append("<table><tbody>")
            append(
                "<tr><td>mode</td><td>${if (report.enforceLiveBreakers) "enforced" else "observe-only"}</td></tr>",
            )
            append(
                "<tr><td>round trips</td><td>${report.maxRoundTrips} per " +
                    "${report.roundTripWindowMs / 1000}s</td></tr>",
            )
            append(
                "<tr><td>broker rejections</td><td>${report.maxRejections} per " +
                    "${report.rejectionWindowMs / 1000}s</td></tr>",
            )
            append("</tbody></table>")
            if (!report.enforceLiveBreakers && report.trips.isNotEmpty()) {
                val first = report.trips.first()
                append("<p class=\"warning\"><strong>LIVE BEHAVIOR WARNING:</strong> ")
                append("the runaway breaker would have halted this strategy ${report.trips.size} time(s); first at ")
                append(htmlEscape(Instant.ofEpochMilli(first.timestampMs).toString()))
                append(" [${htmlEscape(first.strategyId)}]: ${htmlEscape(first.reason())}</p>")
            }
        }
}
