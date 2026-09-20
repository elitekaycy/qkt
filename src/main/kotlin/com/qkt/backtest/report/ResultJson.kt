package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.ReplayInputReport
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.evidence.EvidenceJson

/**
 * The `result.json` artifact (schema `qkt-backtest-result-v1`): run evidence, accounting, the
 * artifact index, trade audit summary, global and per-strategy performance, and book sections.
 */
internal object ResultJson {
    fun render(result: BacktestResult): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"qkt-backtest-result-v1\",\n")
        sb.append("  \"schemaVersion\": 1,\n")
        sb.append("  \"cadence\": ").append(ReportSerializer.jsonString(result.cadence.name)).append(",\n")
        sb.append("  \"inputSummary\": ").append(renderInputSummary(result.inputSummary)).append(",\n")
        sb.append("  \"evidence\": ").append(result.evidence?.let(EvidenceJson::render) ?: "null").append(",\n")
        sb.append("  \"accounting\": ").append(AccountingJson.render(result.accounting)).append(",\n")
        sb.append("  \"artifacts\": ").append(renderArtifacts(result)).append(",\n")
        sb.append("  \"tradeSummary\": ").append(renderTradeSummary(result)).append(",\n")
        sb.append("  \"global\": ").append(PerformanceReportJson.render(result.global, indent = 2)).append(",\n")
        sb.append("  \"perStrategy\": {")
        if (result.perStrategy.isNotEmpty()) {
            sb.append('\n')
            val entries = result.perStrategy.entries.toList()
            for ((i, e) in entries.withIndex()) {
                sb
                    .append("    ")
                    .append(ReportSerializer.jsonString(e.key))
                    .append(": ")
                    .append(PerformanceReportJson.render(e.value, indent = 4))
                if (i != entries.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("  }")
        } else {
            sb.append("}")
        }
        sb.append(",\n  \"bookAnalytics\": ").append(BookJson.renderAnalytics(result.bookAnalytics))
        sb.append(",\n  \"bookRisk\": ").append(BookJson.renderRisk(result.bookRisk))
        sb.append(",\n  \"runawayBreaker\": ").append(renderRunawayBreaker(result.runawayBreaker))
        sb.append("\n}")
        return sb.toString()
    }

    private fun renderInputSummary(report: ReplayInputReport?): String {
        if (report == null) return "null"
        return buildString {
            append("{\"attemptedFeedTicks\": ").append(report.attemptedFeedTicks)
            append(", \"liveTicks\": ").append(report.liveTicks)
            append(", \"warmupTicks\": ").append(report.warmupTicks)
            append(", \"warmupCandles\": ").append(report.warmupCandles)
            append(", \"liveCandles\": ").append(report.liveCandles)
            append(", \"malformedTicks\": ").append(report.malformedTicks)
            append(", \"droppedLateTicks\": ").append(report.droppedLateTicks)
            append(", \"streamCandles\": {")
            report.streamCandles.entries.sortedBy { it.key }.forEachIndexed { index, (key, count) ->
                if (index > 0) append(',')
                append(ReportSerializer.jsonString(key)).append(':').append(count)
            }
            append('}')
            append(", \"strategyCandleEvaluations\": {")
            report.strategyCandleEvaluations.entries.sortedBy { it.key }.forEachIndexed { index, (key, count) ->
                if (index > 0) append(',')
                append(ReportSerializer.jsonString(key)).append(':').append(count)
            }
            append('}')
            append("}")
        }
    }

    private fun renderRunawayBreaker(report: RunawayBreakerReport?): String {
        if (report == null) return "null"
        return buildString {
            append("{\"enforceLiveBreakers\": ").append(report.enforceLiveBreakers)
            append(", \"maxRoundTrips\": ").append(report.maxRoundTrips)
            append(", \"roundTripWindowMs\": ").append(report.roundTripWindowMs)
            append(", \"maxRejections\": ").append(report.maxRejections)
            append(", \"rejectionWindowMs\": ").append(report.rejectionWindowMs)
            append(", \"trips\": [")
            append(
                report.trips.joinToString(",") { trip ->
                    "{\"timestampMs\": ${trip.timestampMs}, " +
                        "\"strategyId\": ${ReportSerializer.jsonString(trip.strategyId)}, " +
                        "\"rule\": ${ReportSerializer.jsonString(trip.rule.name.lowercase())}, " +
                        "\"count\": ${trip.count}, \"threshold\": ${trip.threshold}, " +
                        "\"windowMs\": ${trip.windowMs}}"
                },
            )
            append("]}")
        }
    }

    private fun renderArtifacts(result: BacktestResult): String =
        buildString {
            append("{\"resultJson\": \"result.json\"")
            append(", \"tradesCsv\": \"trades.csv\"")
            append(", \"rejectionsCsv\": \"rejections.csv\"")
            append(", \"ordersJsonl\": \"orders.jsonl\"")
            append(", \"pnlComponentsCsv\": \"pnl_components.csv\"")
            append(", \"manifestJson\": \"manifest.json\"")
            append(", \"equityGlobalCsv\": \"equity_global.csv\"")
            append(", \"equityStrategyCsv\": {")
            append(
                result.perStrategy.keys
                    .sorted()
                    .joinToString(",") {
                        "${ReportSerializer.jsonString(it)}: ${ReportSerializer.jsonString(EquityCsv.fileName(it))}"
                    },
            )
            append("}")
            if (result.bookRisk != null) append(", \"bookRiskCsv\": \"book_risk.csv\"")
            append(", \"html\": \"report.html\"")
            append("}")
        }

    private fun renderTradeSummary(result: BacktestResult): String {
        val summary = TradeAuditSummaries.from(result)

        return buildString {
            append("{\"fills\": ").append(summary.fills)
            append(", \"buyFills\": ").append(summary.buyFills)
            append(", \"sellFills\": ").append(summary.sellFills)
            append(", \"sideAttribution\": ").append(ReportSerializer.jsonString(summary.sideAttribution))
            append(", \"longEntryFills\": ").append(summary.longEntryFills)
            append(", \"shortEntryFills\": ").append(summary.shortEntryFills)
            append(", \"longExitFills\": ").append(summary.longExitFills)
            append(", \"shortExitFills\": ").append(summary.shortExitFills)
            append(", \"unknownPositionFills\": ").append(summary.unknownPositionFills)
            append(", \"positionAttribution\": ").append(ReportSerializer.jsonString(summary.positionAttribution))
            append(", \"buyRealized\": ").append(ReportSerializer.jsonBigDecimal(summary.buyRealized))
            append(", \"sellRealized\": ").append(ReportSerializer.jsonBigDecimal(summary.sellRealized))
            append(", \"grossProfit\": ").append(ReportSerializer.jsonBigDecimal(summary.grossProfit))
            append(", \"grossLoss\": ").append(ReportSerializer.jsonBigDecimal(summary.grossLoss))
            append(", \"rejections\": ").append(summary.rejections)
            append(", \"rejectionRate\": ").append(ReportSerializer.jsonNullableBigDecimal(summary.rejectionRate))
            append(", \"riskAuditedFills\": ").append(summary.riskAuditedFills)
            append(", \"minRiskUsd\": ").append(ReportSerializer.jsonNullableBigDecimal(summary.minRiskUsd))
            append(", \"avgRiskUsd\": ").append(ReportSerializer.jsonNullableBigDecimal(summary.avgRiskUsd))
            append(", \"maxRiskUsd\": ").append(ReportSerializer.jsonNullableBigDecimal(summary.maxRiskUsd))
            append(", \"tradedNotional\": ").append(ReportSerializer.jsonBigDecimal(summary.tradedNotional))
            append(", \"maxFillNotional\": ").append(ReportSerializer.jsonNullableBigDecimal(summary.maxFillNotional))
            append("}")
        }
    }
}
