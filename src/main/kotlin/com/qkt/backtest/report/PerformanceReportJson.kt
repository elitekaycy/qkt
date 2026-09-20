package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One [PerformanceReport] as a pretty-printed JSON object inside `result.json`, indented to sit
 * at the given depth: headline metrics, daily PnL, drawdown periods, Monte Carlo tail, and the
 * full equity curve.
 */
internal object PerformanceReportJson {
    fun render(
        r: PerformanceReport,
        indent: Int,
    ): String {
        val pad = " ".repeat(indent)
        val sb = StringBuilder("{")

        fun field(
            name: String,
            value: String,
            last: Boolean = false,
        ) {
            sb
                .append('\n')
                .append(pad)
                .append("  ")
                .append(ReportSerializer.jsonString(name))
                .append(": ")
                .append(value)
            if (!last) sb.append(",")
        }
        field("realizedTotal", ReportSerializer.jsonBigDecimal(r.realizedTotal))
        field("unrealizedTotal", ReportSerializer.jsonBigDecimal(r.unrealizedTotal))
        field("totalPnL", ReportSerializer.jsonBigDecimal(r.totalPnL))
        field("commissionPaid", ReportSerializer.jsonBigDecimal(r.commissionPaid))
        field("swapPaid", ReportSerializer.jsonBigDecimal(r.swapPaid))
        field("tradeCount", r.tradeCount.toString())
        field("winRate", ReportSerializer.jsonBigDecimal(r.winRate))
        field("maxDrawdown", ReportSerializer.jsonBigDecimal(r.maxDrawdown))
        field("profitFactor", ReportSerializer.jsonNullableBigDecimal(r.profitFactor))
        field("avgWin", ReportSerializer.jsonBigDecimal(r.avgWin))
        field("avgLoss", ReportSerializer.jsonBigDecimal(r.avgLoss))
        field("largestWin", ReportSerializer.jsonBigDecimal(r.largestWin))
        field("largestLoss", ReportSerializer.jsonBigDecimal(r.largestLoss))
        field("maxConsecutiveLosses", r.maxConsecutiveLosses.toString())
        field("sharpeRatio", ReportSerializer.jsonNullableBigDecimal(r.sharpeRatio))
        field("calmarRatio", ReportSerializer.jsonNullableBigDecimal(r.calmarRatio))
        field("sortinoRatio", ReportSerializer.jsonNullableBigDecimal(r.sortinoRatio))
        field("turnover", ReportSerializer.jsonBigDecimal(r.turnover))
        field("maxDailyDrawdown", ReportSerializer.jsonBigDecimal(r.maxDailyDrawdown))
        field("dailyPnL", renderDailyPnl(r.dailyPnL))
        field("drawdownPeriods", renderDrawdownPeriods(r.drawdownPeriods))
        field("monteCarlo", renderMonteCarlo(r.monteCarlo))
        sb.append("\n").append(pad).append("  \"equityCurve\": [")
        if (r.equityCurve.isNotEmpty()) {
            sb.append('\n')
            val entries = r.equityCurve
            for ((i, s) in entries.withIndex()) {
                sb
                    .append(pad)
                    .append("    {\"timestamp\": ")
                    .append(s.timestamp)
                    .append(", \"iso\": ")
                    .append(ReportSerializer.jsonString(ReportSerializer.isoUtc(s.timestamp)))
                    .append(", \"equity\": ")
                    .append(ReportSerializer.jsonBigDecimal(s.equity))
                    .append("}")
                if (i != entries.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append(pad).append("  ]")
        } else {
            sb.append("]")
        }
        sb.append('\n').append(pad).append("}")
        return sb.toString()
    }

    private fun renderDailyPnl(dailyPnL: Map<LocalDate, BigDecimal>): String =
        buildString {
            append("{")
            append(
                dailyPnL.entries
                    .sortedBy { it.key }
                    .joinToString(",") {
                        val key = ReportSerializer.jsonString(it.key.toString())
                        val value = ReportSerializer.jsonBigDecimal(it.value)
                        "$key: $value"
                    },
            )
            append("}")
        }

    private fun renderDrawdownPeriods(periods: List<DrawdownPeriod>): String =
        buildString {
            append("[")
            append(
                periods.joinToString(",") {
                    "{\"peakTimestamp\": ${it.peakTimestamp}, " +
                        "\"peakIso\": ${ReportSerializer.jsonString(ReportSerializer.isoUtc(it.peakTimestamp))}, " +
                        "\"troughTimestamp\": ${it.troughTimestamp}, " +
                        "\"troughIso\": ${ReportSerializer.jsonString(ReportSerializer.isoUtc(it.troughTimestamp))}, " +
                        "\"recoveryTimestamp\": ${it.recoveryTimestamp?.toString() ?: "null"}, " +
                        "\"recoveryIso\": ${
                            it.recoveryTimestamp
                                ?.let { ts -> ReportSerializer.jsonString(ReportSerializer.isoUtc(ts)) }
                                ?: "null"
                        }, " +
                        "\"depthPct\": ${ReportSerializer.jsonBigDecimal(it.depthPct)}, " +
                        "\"durationMs\": ${it.durationMs}, " +
                        "\"ongoing\": ${it.ongoing}}"
                },
            )
            append("]")
        }

    private fun renderMonteCarlo(mc: MonteCarloSummary?): String {
        if (mc == null) return "null"
        return buildString {
            append("{\"simulations\": ").append(mc.simulations)
            append(", \"finalEquityP5\": ").append(ReportSerializer.jsonBigDecimal(mc.finalEquityP5))
            append(", \"finalEquityP50\": ").append(ReportSerializer.jsonBigDecimal(mc.finalEquityP50))
            append(", \"finalEquityP95\": ").append(ReportSerializer.jsonBigDecimal(mc.finalEquityP95))
            append(", \"maxDrawdownP5\": ").append(ReportSerializer.jsonBigDecimal(mc.maxDrawdownP5))
            append(", \"maxDrawdownP95\": ").append(ReportSerializer.jsonBigDecimal(mc.maxDrawdownP95))
            append(", \"probabilityNegativeFinal\": ")
                .append(ReportSerializer.jsonBigDecimal(mc.probabilityNegativeFinal))
            append("}")
        }
    }
}
