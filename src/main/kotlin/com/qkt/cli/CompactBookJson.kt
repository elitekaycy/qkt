package com.qkt.cli

import com.qkt.backtest.BookAnalytics
import com.qkt.backtest.BookRiskReport
import com.qkt.backtest.report.ReportSerializer.jsonString
import java.math.BigDecimal

/**
 * The portfolio-book objects of `qkt backtest --json`: the book-risk summary and cross-strategy
 * book analytics. Both render `null` on a single-strategy run.
 */
internal object CompactBookJson {
    /** Book-risk summary object for `--json` (full series is in the `--report` book_risk.csv). */
    fun bookRiskJson(br: BookRiskReport?): String {
        if (br == null) return "null"
        return buildString {
            append("{\"bookVol\":").append(br.bookVol?.toPlainString() ?: "null")
            append(",\"maxGrossExposure\":").append(br.maxGrossExposure.toPlainString())
            append(",\"maxNetExposure\":").append(br.maxNetExposure.toPlainString())
            append(",\"samples\":").append(br.series.size)
            append(",\"events\":").append(br.events.size)
            append("}")
        }
    }

    /** Cross-strategy book analytics as a JSON object, or null on a single-strategy run. */
    fun bookAnalyticsJson(ba: BookAnalytics?): String {
        if (ba == null) return "null"
        return buildString {
            append("{\"contributionToReturn\":").append(mapNumberJson(ba.contributionToReturn))
            append(",\"riskContribution\":").append(mapNumberJson(ba.riskContribution))
            append(",\"drawdownContribution\":").append(mapNumberJson(ba.drawdownContribution))
            append(",\"returnCorrelation\":[")
            append(
                ba.returnCorrelation.joinToString(",") {
                    "{\"a\":${jsonString(it.a)},\"b\":${jsonString(it.b)}," +
                        "\"correlation\":${it.correlation.toPlainString()}}"
                },
            )
            append("]}")
        }
    }

    private fun mapNumberJson(m: Map<String, BigDecimal>): String =
        buildString {
            append("{")
            append(
                m.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${jsonString(it.key)}:${it.value.toPlainString()}" },
            )
            append("}")
        }
}
