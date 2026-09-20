package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ReplayInputReport
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.backtest.report.ReportSerializer.jsonString
import com.qkt.backtest.report.TradeAuditSummaries
import com.qkt.evidence.EvidenceJson
import java.io.PrintStream

/**
 * The single-line JSON form of `qkt backtest` output (schema `qkt-backtest-result-v1`), for piping
 * into tooling. Field order is fixed; map-valued fields are key-sorted for deterministic output.
 */
internal object JsonReportPrinter {
    private const val RESULT_SCHEMA = "qkt-backtest-result-v1"
    private const val RESULT_SCHEMA_VERSION = 1

    fun print(
        r: BacktestResult,
        out: PrintStream,
        brokerKind: BrokerKind,
    ) {
        val g = r.global
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"schema\":\"").append(RESULT_SCHEMA).append("\",")
        sb.append("\"schemaVersion\":").append(RESULT_SCHEMA_VERSION).append(',')
        sb.append("\"trades\":").append(g.tradeCount).append(',')
        sb.append("\"finalRealized\":").append(g.realizedTotal.toPlainString()).append(',')
        sb.append("\"finalUnrealized\":").append(g.unrealizedTotal.toPlainString()).append(',')
        sb.append("\"totalPnL\":").append(g.totalPnL.toPlainString()).append(',')
        sb.append("\"commissionPaid\":").append(g.commissionPaid.toPlainString()).append(',')
        sb.append("\"swapPaid\":").append(g.swapPaid.toPlainString()).append(',')
        sb.append("\"winRate\":").append(g.winRate.toPlainString()).append(',')
        sb.append("\"maxDrawdown\":").append(g.maxDrawdown.toPlainString()).append(',')
        sb.append("\"profitFactor\":").append(g.profitFactor?.toPlainString() ?: "null").append(',')
        sb.append("\"avgWin\":").append(g.avgWin.toPlainString()).append(',')
        sb.append("\"avgLoss\":").append(g.avgLoss.toPlainString()).append(',')
        sb.append("\"largestWin\":").append(g.largestWin.toPlainString()).append(',')
        sb.append("\"largestLoss\":").append(g.largestLoss.toPlainString()).append(',')
        sb.append("\"maxConsecutiveLosses\":").append(g.maxConsecutiveLosses).append(',')
        sb.append("\"sharpeRatio\":").append(g.sharpeRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"calmarRatio\":").append(g.calmarRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"sortinoRatio\":").append(g.sortinoRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"turnover\":").append(g.turnover.toPlainString()).append(',')
        sb.append("\"executionModel\":\"").append(brokerKind.name.lowercase()).append("\",")
        sb.append("\"maxDailyDrawdown\":").append(g.maxDailyDrawdown.toPlainString()).append(',')
        sb.append("\"dailyPnL\":{")
        sb.append(
            g.dailyPnL.entries
                .sortedBy { it.key }
                .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
        )
        sb.append("},")
        sb.append("\"halts\":").append(r.halts.size).append(',')
        sb.append("\"runawayBreaker\":").append(runawayBreakerJson(r.runawayBreaker)).append(',')
        sb.append("\"inputSummary\":").append(inputSummaryJson(r.inputSummary)).append(',')
        sb.append("\"cadence\":\"").append(r.cadence.name).append("\",")
        sb.append("\"conditionalAutocorr\":").append(AutocorrJson.render(r.conditionalAutocorr)).append(',')
        sb.append("\"tradeSummary\":").append(tradeSummaryJson(r)).append(',')
        sb.append("\"global\":").append(CompactReportJson.reportJson(g)).append(',')
        sb.append("\"perStrategy\":{")
        sb.append(
            r.perStrategy.entries
                .sortedBy { it.key }
                .joinToString(",") { (id, s) -> "${jsonString(id)}:${CompactReportJson.strategyJson(s)}" },
        )
        sb.append("},")
        sb.append("\"bookAnalytics\":").append(CompactBookJson.bookAnalyticsJson(r.bookAnalytics)).append(',')
        sb.append("\"bookRisk\":").append(CompactBookJson.bookRiskJson(r.bookRisk)).append(',')
        sb.append("\"evidence\":").append(r.evidence?.let(EvidenceJson::render) ?: "null").append(',')
        sb.append("\"monteCarlo\":").append(CompactReportJson.monteCarloJson(g.monteCarlo))
        sb.append('}')
        out.println(sb.toString())
    }

    private fun inputSummaryJson(report: ReplayInputReport?): String {
        if (report == null) return "null"
        return buildString {
            append("{\"attemptedFeedTicks\":").append(report.attemptedFeedTicks)
            append(",\"liveTicks\":").append(report.liveTicks)
            append(",\"warmupTicks\":").append(report.warmupTicks)
            append(",\"warmupCandles\":").append(report.warmupCandles)
            append(",\"liveCandles\":").append(report.liveCandles)
            append(",\"malformedTicks\":").append(report.malformedTicks)
            append(",\"droppedLateTicks\":").append(report.droppedLateTicks)
            append(",\"streamCandles\":{")
            report.streamCandles.entries.sortedBy { it.key }.forEachIndexed { index, (key, count) ->
                if (index > 0) append(',')
                append(jsonString(key)).append(':').append(count)
            }
            append('}')
            append(",\"strategyCandleEvaluations\":{")
            report.strategyCandleEvaluations.entries.sortedBy { it.key }.forEachIndexed { index, (key, count) ->
                if (index > 0) append(',')
                append(jsonString(key)).append(':').append(count)
            }
            append('}')
            append("}")
        }
    }

    private fun runawayBreakerJson(report: RunawayBreakerReport?): String {
        if (report == null) return "null"
        return buildString {
            append("{\"enforceLiveBreakers\":").append(report.enforceLiveBreakers)
            append(",\"maxRoundTrips\":").append(report.maxRoundTrips)
            append(",\"roundTripWindowMs\":").append(report.roundTripWindowMs)
            append(",\"maxRejections\":").append(report.maxRejections)
            append(",\"rejectionWindowMs\":").append(report.rejectionWindowMs)
            append(",\"trips\":[")
            append(
                report.trips.joinToString(",") { trip ->
                    "{\"timestampMs\":${trip.timestampMs},\"strategyId\":" +
                        "${jsonString(trip.strategyId)}," +
                        "\"rule\":${jsonString(trip.rule.name.lowercase())},\"count\":${trip.count}," +
                        "\"threshold\":${trip.threshold},\"windowMs\":${trip.windowMs}}"
                },
            )
            append("]}")
        }
    }

    private fun tradeSummaryJson(result: BacktestResult): String {
        val summary = TradeAuditSummaries.from(result)

        return buildString {
            append("{\"fills\":").append(summary.fills)
            append(",\"buyFills\":").append(summary.buyFills)
            append(",\"sellFills\":").append(summary.sellFills)
            append(",\"sideAttribution\":").append(jsonString(summary.sideAttribution))
            append(",\"longEntryFills\":").append(summary.longEntryFills)
            append(",\"shortEntryFills\":").append(summary.shortEntryFills)
            append(",\"longExitFills\":").append(summary.longExitFills)
            append(",\"shortExitFills\":").append(summary.shortExitFills)
            append(",\"unknownPositionFills\":").append(summary.unknownPositionFills)
            append(",\"positionAttribution\":").append(jsonString(summary.positionAttribution))
            append(",\"buyRealized\":").append(summary.buyRealized.toPlainString())
            append(",\"sellRealized\":").append(summary.sellRealized.toPlainString())
            append(",\"grossProfit\":").append(summary.grossProfit.toPlainString())
            append(",\"grossLoss\":").append(summary.grossLoss.toPlainString())
            append(",\"rejections\":").append(summary.rejections)
            append(",\"rejectionRate\":").append(summary.rejectionRate?.toPlainString() ?: "null")
            append(",\"riskAuditedFills\":").append(summary.riskAuditedFills)
            append(",\"minRiskUsd\":").append(summary.minRiskUsd?.toPlainString() ?: "null")
            append(",\"avgRiskUsd\":").append(summary.avgRiskUsd?.toPlainString() ?: "null")
            append(",\"maxRiskUsd\":").append(summary.maxRiskUsd?.toPlainString() ?: "null")
            append(",\"tradedNotional\":").append(summary.tradedNotional.toPlainString())
            append(",\"maxFillNotional\":").append(summary.maxFillNotional?.toPlainString() ?: "null")
            append("}")
        }
    }
}
