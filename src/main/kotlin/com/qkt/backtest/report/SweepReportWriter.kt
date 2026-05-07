package com.qkt.backtest.report

import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.sweep.SweepResult
import com.qkt.backtest.sweep.SweepRun
import java.nio.file.Files
import java.nio.file.Path

class SweepReportWriter(
    private val dir: Path,
) {
    private val safeLabel = Regex("[A-Za-z0-9_-]+")

    fun <C> write(result: SweepResult<C>) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (run in result.runs) {
            require(safeLabel.matches(run.label)) {
                "Unsafe label for filesystem write: ${run.label}"
            }
            val cs = run.config.toString()
            require(!cs.contains(',') && !cs.contains('"') && !cs.contains('\n')) {
                "Config string for label='${run.label}' must not contain comma, double-quote, or newline: $cs"
            }
        }

        Files.writeString(dir.resolve("sweep_summary.csv"), renderCsv(result))
        Files.writeString(dir.resolve("sweep_summary.json"), renderJson(result))

        val runsDir = dir.resolve("runs")
        Files.createDirectories(runsDir)
        for (run in result.runs) {
            val perRun = runsDir.resolve(run.label)
            Files.createDirectories(perRun)
            BacktestReportWriter(perRun).write(run.result)
        }
    }

    private fun <C> renderCsv(result: SweepResult<C>): String {
        val sb =
            StringBuilder(
                "label,config,totalPnL,sharpeRatio,maxDrawdown,winRate,tradeCount,profitFactor,calmarRatio\n",
            )
        for (run in result.runs) {
            val r: PerformanceReport = run.result.global
            sb
                .append(run.label).append(',')
                .append(run.config.toString()).append(',')
                .append(r.totalPnL.toPlainString()).append(',')
                .append(r.sharpeRatio?.toPlainString() ?: "").append(',')
                .append(r.maxDrawdown.toPlainString()).append(',')
                .append(r.winRate.toPlainString()).append(',')
                .append(r.tradeCount).append(',')
                .append(r.profitFactor?.toPlainString() ?: "").append(',')
                .append(r.calmarRatio?.toPlainString() ?: "").append('\n')
        }
        return sb.toString()
    }

    private fun <C> renderJson(result: SweepResult<C>): String {
        val sb = StringBuilder("[")
        if (result.runs.isNotEmpty()) {
            sb.append('\n')
            for ((i, run) in result.runs.withIndex()) {
                sb.append("  ").append(renderRunJson(run))
                if (i != result.runs.size - 1) sb.append(",")
                sb.append('\n')
            }
        }
        sb.append("]")
        return sb.toString()
    }

    private fun <C> renderRunJson(run: SweepRun<C>): String {
        val r = run.result.global
        val sb = StringBuilder("{")
        sb.append("\n    \"label\": ").append(ReportSerializer.jsonString(run.label)).append(",")
        sb.append("\n    \"config\": ").append(ReportSerializer.jsonString(run.config.toString())).append(",")
        sb.append("\n    \"totalPnL\": ").append(ReportSerializer.jsonBigDecimal(r.totalPnL)).append(",")
        sb.append("\n    \"sharpeRatio\": ").append(ReportSerializer.jsonNullableBigDecimal(r.sharpeRatio)).append(",")
        sb.append("\n    \"maxDrawdown\": ").append(ReportSerializer.jsonBigDecimal(r.maxDrawdown)).append(",")
        sb.append("\n    \"winRate\": ").append(ReportSerializer.jsonBigDecimal(r.winRate)).append(",")
        sb.append("\n    \"tradeCount\": ").append(r.tradeCount).append(",")
        sb.append("\n    \"profitFactor\": ").append(ReportSerializer.jsonNullableBigDecimal(r.profitFactor)).append(",")
        sb.append("\n    \"calmarRatio\": ").append(ReportSerializer.jsonNullableBigDecimal(r.calmarRatio))
        sb.append("\n  }")
        return sb.toString()
    }
}
