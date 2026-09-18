package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.EvidenceJson
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Writes a single [com.qkt.backtest.BacktestResult] to a directory as a bundle of
 * machine-readable artifacts (`result.json`, per-strategy equity curves, trades and
 * rejections CSVs) plus a rendered `report.html` summary.
 *
 * One writer per output directory; call [write] once per result. Strategy ids may contain
 * colon-separated `[A-Za-z0-9_-]+` segments. Colons are encoded when ids are embedded in filenames;
 * anything outside that grammar fails fast before the writer touches the filesystem.
 */
class BacktestReportWriter(
    private val dir: Path,
) {
    private val safeId = Regex("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")

    /**
     * Emit every artifact for [result] into the writer's directory. Overwrites any
     * existing files; the directory itself must exist, be writable, and be free of
     * unsafe strategy ids before the call.
     */
    fun write(result: BacktestResult) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (id in result.perStrategy.keys) {
            require(safeId.matches(id)) { "Unsafe strategyId for filesystem write: $id" }
        }

        Files.writeString(dir.resolve("result.json"), renderJson(result))
        Files.writeString(dir.resolve("equity_global.csv"), EquityCsv.render(result.global.equityCurve))
        for ((id, report) in result.perStrategy) {
            Files.writeString(dir.resolve(EquityCsv.fileName(id)), EquityCsv.render(report.equityCurve))
        }
        Files.writeString(dir.resolve("trades.csv"), TradesCsv.render(result.trades))
        Files.writeString(dir.resolve("financing.csv"), FinancingCsv.render(result.global.swapPaid))
        Files.writeString(dir.resolve("rejections.csv"), RejectionsCsv.render(result.rejections))
        Files.writeString(dir.resolve("orders.jsonl"), OrderDecisionsJsonl.render(result))
        Files.writeString(dir.resolve("pnl_components.csv"), PnlComponentsCsv.render(result))
        result.bookRisk?.let { Files.writeString(dir.resolve("book_risk.csv"), BookRiskCsv.render(it)) }
        HtmlReportWriter().write(result, dir.resolve("report.html"))
        Files.writeString(dir.resolve("manifest.json"), renderManifest(result))
    }

    private fun fileId(strategyId: String): String = strategyId.replace(":", "%3A")

    private fun renderJson(result: BacktestResult): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"qkt-backtest-result-v1\",\n")
        sb.append("  \"schemaVersion\": 1,\n")
        sb.append("  \"cadence\": ").append(ReportSerializer.jsonString(result.cadence.name)).append(",\n")
        sb.append("  \"inputSummary\": ").append(renderInputSummary(result.inputSummary)).append(",\n")
        sb.append("  \"evidence\": ").append(result.evidence?.let(EvidenceJson::render) ?: "null").append(",\n")
        sb.append("  \"accounting\": ").append(renderAccounting(result.accounting)).append(",\n")
        sb.append("  \"artifacts\": ").append(renderArtifacts(result)).append(",\n")
        sb.append("  \"tradeSummary\": ").append(renderTradeSummary(result)).append(",\n")
        sb.append("  \"global\": ").append(renderReport(result.global, indent = 2)).append(",\n")
        sb.append("  \"perStrategy\": {")
        if (result.perStrategy.isNotEmpty()) {
            sb.append('\n')
            val entries = result.perStrategy.entries.toList()
            for ((i, e) in entries.withIndex()) {
                sb
                    .append("    ")
                    .append(ReportSerializer.jsonString(e.key))
                    .append(": ")
                    .append(renderReport(e.value, indent = 4))
                if (i != entries.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("  }")
        } else {
            sb.append("}")
        }
        sb.append(",\n  \"bookAnalytics\": ").append(renderBookAnalytics(result.bookAnalytics))
        sb.append(",\n  \"bookRisk\": ").append(renderBookRiskJson(result.bookRisk))
        sb.append(",\n  \"runawayBreaker\": ").append(renderRunawayBreaker(result.runawayBreaker))
        sb.append("\n}")
        return sb.toString()
    }

    private fun renderInputSummary(report: com.qkt.backtest.ReplayInputReport?): String {
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

    private fun renderRunawayBreaker(report: com.qkt.backtest.RunawayBreakerReport?): String {
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
                        "${ReportSerializer.jsonString(it)}: ${ReportSerializer.jsonString("equity_${fileId(it)}.csv")}"
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

    private fun renderManifest(result: BacktestResult): String {
        val artifacts =
            buildList {
                add("result.json")
                add("equity_global.csv")
                addAll(
                    result.perStrategy.keys
                        .sorted()
                        .map { "equity_${fileId(it)}.csv" },
                )
                add("trades.csv")
                add("financing.csv")
                add("rejections.csv")
                add("orders.jsonl")
                add("pnl_components.csv")
                if (result.bookRisk != null) add("book_risk.csv")
                add("report.html")
            }
        return buildString {
            append("{\n")
            append("  \"schema\": \"qkt-report-bundle-v1\",\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"selfHashIncluded\": false,\n")
            append("  \"generatedAt\": ")
                .append(result.evidence?.buildTimestamp?.let(ReportSerializer::jsonString) ?: "null")
                .append(",\n")
            append("  \"qktVersion\": ")
                .append(result.evidence?.qktVersion?.let(ReportSerializer::jsonString) ?: "null")
                .append(",\n")
            append("  \"gitSha\": ")
                .append(result.evidence?.gitSha?.let(ReportSerializer::jsonString) ?: "null")
                .append(",\n")
            append("  \"artifacts\": [")
            if (artifacts.isNotEmpty()) {
                append('\n')
                for ((index, artifact) in artifacts.withIndex()) {
                    val path = dir.resolve(artifact)
                    append("    {\"path\": ")
                        .append(ReportSerializer.jsonString(artifact))
                        .append(", \"sha256\": ")
                        .append(ReportSerializer.jsonString(EvidenceHasher.sha256(path)))
                        .append(", \"bytes\": ")
                        .append(Files.size(path))
                        .append("}")
                    if (index != artifacts.size - 1) append(',')
                    append('\n')
                }
                append("  ]\n")
            } else {
                append("]\n")
            }
            append("}")
        }
    }

    private fun renderBookAnalytics(ba: com.qkt.backtest.BookAnalytics?): String {
        if (ba == null) return "null"

        fun mapJson(m: Map<String, java.math.BigDecimal>): String =
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

    private fun renderBookRiskJson(br: com.qkt.backtest.BookRiskReport?): String {
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

    private fun renderAccounting(snapshot: com.qkt.accounting.AccountingSnapshot?): String {
        if (snapshot == null) return "null"
        return buildString {
            append("{\"accountCurrency\": ")
                .append(ReportSerializer.jsonString(snapshot.accountCurrency))
            append(", \"missingPolicy\": ")
                .append(ReportSerializer.jsonString(snapshot.missingPolicy))
            append(", \"source\": ")
                .append(ReportSerializer.jsonString(snapshot.source))
            append(", \"configuredSymbols\": {")
            append(
                snapshot.configuredSymbols.entries.sortedBy { it.key }.joinToString(",") {
                    "${ReportSerializer.jsonString(it.key)}: ${ReportSerializer.jsonString(it.value)}"
                },
            )
            append("}, \"conversions\": [")
            append(
                snapshot.conversions.joinToString(",") {
                    "{\"from\": ${ReportSerializer.jsonString(it.from)}, " +
                        "\"to\": ${ReportSerializer.jsonString(it.to)}, " +
                        "\"rate\": ${ReportSerializer.jsonBigDecimal(it.rate)}, " +
                        "\"timestamp\": ${it.timestamp}, " +
                        "\"source\": ${ReportSerializer.jsonString(it.source)}}"
                },
            )
            append("], \"warnings\": [")
            append(snapshot.warnings.joinToString(",") { ReportSerializer.jsonString(it) })
            append("], \"costKinds\": [")
            append(snapshot.supportedCostKinds.joinToString(",") { ReportSerializer.jsonString(it) })
            append("]}")
        }
    }

    private fun renderReport(
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

    private fun renderDailyPnl(dailyPnL: Map<java.time.LocalDate, BigDecimal>): String =
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

    private fun renderDrawdownPeriods(periods: List<com.qkt.backtest.DrawdownPeriod>): String =
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

    private fun renderMonteCarlo(mc: com.qkt.backtest.MonteCarloSummary?): String {
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
