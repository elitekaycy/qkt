package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.TradeRecord
import com.qkt.events.RiskRejectedEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a single [com.qkt.backtest.BacktestResult] to a directory as a bundle of
 * machine-readable artifacts (`result.json`, per-strategy equity curves, trades and
 * rejections CSVs) plus a rendered `report.html` summary.
 *
 * One writer per output directory; call [write] once per result. Strategy ids must
 * match `[A-Za-z0-9_-]+` so they're safe to embed in filenames — anything else fails
 * fast before the writer touches the filesystem.
 */
class BacktestReportWriter(
    private val dir: Path,
) {
    private val safeId = Regex("[A-Za-z0-9_-]+")

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
        Files.writeString(dir.resolve("equity_global.csv"), renderEquityCsv(result.global.equityCurve))
        for ((id, report) in result.perStrategy) {
            Files.writeString(dir.resolve("equity_$id.csv"), renderEquityCsv(report.equityCurve))
        }
        Files.writeString(dir.resolve("trades.csv"), renderTradesCsv(result.trades))
        Files.writeString(dir.resolve("rejections.csv"), renderRejectionsCsv(result.rejections))
        HtmlReportWriter().write(result, dir.resolve("report.html"))
    }

    private fun renderEquityCsv(curve: List<EquitySample>): String {
        val sb = StringBuilder("timestamp,equity\n")
        for (s in curve) {
            sb
                .append(s.timestamp)
                .append(',')
                .append(s.equity.toPlainString())
                .append('\n')
        }
        return sb.toString()
    }

    private fun renderTradesCsv(trades: List<TradeRecord>): String {
        val sb = StringBuilder("timestamp,strategy,symbol,side,quantity,price,realized,riskUsd,brokerOrderId\n")
        for (r in trades) {
            sb
                .append(r.trade.timestamp)
                .append(',')
                .append(r.strategyId)
                .append(',')
                .append(r.trade.symbol)
                .append(',')
                .append(r.trade.side)
                .append(',')
                .append(r.trade.quantity.toPlainString())
                .append(',')
                .append(r.trade.price.toPlainString())
                .append(',')
                .append(r.realized.toPlainString())
                .append(',')
                .append(r.riskUsd?.toPlainString() ?: "")
                .append(',')
                .append(r.trade.orderId)
                .append('\n')
        }
        return sb.toString()
    }

    private fun renderRejectionsCsv(rejections: List<RiskRejectedEvent>): String {
        val sb = StringBuilder("timestamp,reason,strategy,symbol\n")
        for (e in rejections) {
            sb
                .append(e.timestamp)
                .append(',')
                .append(e.reason)
                .append(',')
                .append(e.request.strategyId)
                .append(',')
                .append(e.request.symbol)
                .append('\n')
        }
        return sb.toString()
    }

    private fun renderJson(result: BacktestResult): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"cadence\": ").append(ReportSerializer.jsonString(result.cadence.name)).append(",\n")
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
        sb.append("\n}")
        return sb.toString()
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
        sb.append(",\n").append(pad).append("  \"equityCurve\": [")
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
}
