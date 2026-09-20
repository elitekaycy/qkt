package com.qkt.cli

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.report.ReportSerializer.jsonString
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * One [PerformanceReport] as a compact JSON object for `qkt backtest --json`: headline metrics,
 * daily PnL, drawdown periods, Monte Carlo tail and equity curve. Numbers are bare JSON numbers.
 */
internal object CompactReportJson {
    /** Compact per-strategy attribution object for `--json` — the full report is in `--report`. */
    fun strategyJson(s: PerformanceReport): String =
        reportJson(
            s,
            aliases =
                mapOf(
                    "realized" to s.realizedTotal.toPlainString(),
                    "unrealized" to s.unrealizedTotal.toPlainString(),
                    "trades" to s.tradeCount.toString(),
                ),
        )

    fun reportJson(
        r: PerformanceReport,
        aliases: Map<String, String> = emptyMap(),
    ): String =
        buildString {
            append("{\"realizedTotal\":").append(r.realizedTotal.toPlainString())
            append(",\"unrealizedTotal\":").append(r.unrealizedTotal.toPlainString())
            append(",\"totalPnL\":").append(r.totalPnL.toPlainString())
            append(",\"commissionPaid\":").append(r.commissionPaid.toPlainString())
            append(",\"swapPaid\":").append(r.swapPaid.toPlainString())
            append(",\"tradeCount\":").append(r.tradeCount)
            append(",\"winRate\":").append(r.winRate.toPlainString())
            append(",\"maxDrawdown\":").append(r.maxDrawdown.toPlainString())
            append(",\"profitFactor\":").append(r.profitFactor?.toPlainString() ?: "null")
            append(",\"avgWin\":").append(r.avgWin.toPlainString())
            append(",\"avgLoss\":").append(r.avgLoss.toPlainString())
            append(",\"largestWin\":").append(r.largestWin.toPlainString())
            append(",\"largestLoss\":").append(r.largestLoss.toPlainString())
            append(",\"maxConsecutiveLosses\":").append(r.maxConsecutiveLosses)
            append(",\"sharpeRatio\":").append(r.sharpeRatio?.toPlainString() ?: "null")
            append(",\"calmarRatio\":").append(r.calmarRatio?.toPlainString() ?: "null")
            append(",\"sortinoRatio\":").append(r.sortinoRatio?.toPlainString() ?: "null")
            append(",\"turnover\":").append(r.turnover.toPlainString())
            append(",\"maxDailyDrawdown\":").append(r.maxDailyDrawdown.toPlainString())
            append(",\"dailyPnL\":").append(dailyPnlJson(r.dailyPnL))
            append(",\"drawdownPeriods\":").append(drawdownPeriodsJson(r.drawdownPeriods))
            append(",\"monteCarlo\":").append(monteCarloJson(r.monteCarlo))
            append(",\"equityCurve\":").append(equityCurveJson(r.equityCurve))
            for ((name, value) in aliases.entries.sortedBy { it.key }) {
                append(',').append(jsonString(name)).append(':').append(value)
            }
            append("}")
        }

    /**
     * The trade-bootstrap Monte-Carlo tail as a JSON object, or `null` when MC was unavailable
     * (fewer than the minimum trades). The drawdown percentiles let a sizing consumer reserve
     * against resampled drawdowns rather than the single realized path; the per-trade equity fan
     * is an HTML-visualization detail and is omitted.
     */
    fun monteCarloJson(mc: MonteCarloSummary?): String {
        if (mc == null) return "null"
        return buildString {
            append("{\"simulations\":").append(mc.simulations)
            append(",\"finalEquityP5\":").append(mc.finalEquityP5.toPlainString())
            append(",\"finalEquityP50\":").append(mc.finalEquityP50.toPlainString())
            append(",\"finalEquityP95\":").append(mc.finalEquityP95.toPlainString())
            append(",\"maxDrawdownP5\":").append(mc.maxDrawdownP5.toPlainString())
            append(",\"maxDrawdownP95\":").append(mc.maxDrawdownP95.toPlainString())
            append(",\"probabilityNegativeFinal\":").append(mc.probabilityNegativeFinal.toPlainString())
            append("}")
        }
    }

    private fun dailyPnlJson(dailyPnL: Map<LocalDate, BigDecimal>): String =
        buildString {
            append("{")
            append(
                dailyPnL.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
            )
            append("}")
        }

    private fun drawdownPeriodsJson(periods: List<DrawdownPeriod>): String =
        buildString {
            append("[")
            append(
                periods.joinToString(",") {
                    "{\"peakTimestamp\":${it.peakTimestamp}," +
                        "\"peakIso\":${isoJson(it.peakTimestamp)}," +
                        "\"troughTimestamp\":${it.troughTimestamp}," +
                        "\"troughIso\":${isoJson(it.troughTimestamp)}," +
                        "\"recoveryTimestamp\":${it.recoveryTimestamp?.toString() ?: "null"}," +
                        "\"recoveryIso\":${
                            it.recoveryTimestamp
                                ?.let(::isoJson)
                                ?: "null"
                        }," +
                        "\"depthPct\":${it.depthPct.toPlainString()}," +
                        "\"durationMs\":${it.durationMs}," +
                        "\"ongoing\":${it.ongoing}}"
                },
            )
            append("]")
        }

    private fun equityCurveJson(curve: List<EquitySample>): String =
        buildString {
            append("[")
            append(
                curve.joinToString(",") {
                    "{\"timestamp\":${it.timestamp}," +
                        "\"iso\":${isoJson(it.timestamp)}," +
                        "\"equity\":${it.equity.toPlainString()}}"
                },
            )
            append("]")
        }

    private fun isoJson(epochMs: Long): String =
        jsonString(
            Instant
                .ofEpochMilli(epochMs)
                .toString(),
        )
}
