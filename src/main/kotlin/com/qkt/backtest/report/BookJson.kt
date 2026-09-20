package com.qkt.backtest.report

import com.qkt.backtest.BookAnalytics
import com.qkt.backtest.BookRiskReport
import java.math.BigDecimal

/**
 * The portfolio-book sections of `result.json`: cross-strategy analytics (return, risk and
 * drawdown contribution plus pairwise correlation) and the book-risk summary. Both are `null`
 * on a single-strategy run.
 */
internal object BookJson {
    fun renderAnalytics(ba: BookAnalytics?): String {
        if (ba == null) return "null"

        fun mapJson(m: Map<String, BigDecimal>): String =
            buildString {
                append("{")
                append(
                    m.entries.sortedBy { it.key }.joinToString(",") {
                        "${ReportSerializer.jsonString(it.key)}:${ReportSerializer.jsonBigDecimal(it.value)}"
                    },
                )
                append("}")
            }
        return buildString {
            append("{\"contributionToReturn\":").append(mapJson(ba.contributionToReturn))
            append(",\"riskContribution\":").append(mapJson(ba.riskContribution))
            append(",\"drawdownContribution\":").append(mapJson(ba.drawdownContribution))
            append(",\"returnCorrelation\":[")
            append(
                ba.returnCorrelation.joinToString(",") { p ->
                    "{\"a\":${ReportSerializer.jsonString(p.a)},\"b\":${ReportSerializer.jsonString(p.b)}," +
                        "\"correlation\":${ReportSerializer.jsonBigDecimal(p.correlation)}}"
                },
            )
            append("]}")
        }
    }

    fun renderRisk(br: BookRiskReport?): String {
        if (br == null) return "null"
        return buildString {
            append("{\"bookVol\": ").append(ReportSerializer.jsonNullableBigDecimal(br.bookVol))
            append(", \"maxGrossExposure\": ").append(ReportSerializer.jsonBigDecimal(br.maxGrossExposure))
            append(", \"maxNetExposure\": ").append(ReportSerializer.jsonBigDecimal(br.maxNetExposure))
            append(", \"samples\": ").append(br.series.size)
            append(", \"events\": ").append(br.events.size)
            append("}")
        }
    }
}
