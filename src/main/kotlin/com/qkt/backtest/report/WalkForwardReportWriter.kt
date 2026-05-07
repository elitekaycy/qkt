package com.qkt.backtest.report

import com.qkt.backtest.EquitySample
import com.qkt.backtest.walkforward.WalkForwardFold
import com.qkt.backtest.walkforward.WalkForwardResult
import java.nio.file.Files
import java.nio.file.Path

class WalkForwardReportWriter(
    private val dir: Path,
) {
    private val safeLabel = Regex("[A-Za-z0-9_-]+")

    fun <C> write(result: WalkForwardResult<C>) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (fold in result.folds) {
            require(safeLabel.matches(fold.winnerLabel)) {
                "Unsafe winner label for filesystem write: ${fold.winnerLabel}"
            }
            val cs = fold.winnerConfig.toString()
            require(!cs.contains(',') && !cs.contains('"') && !cs.contains('\n')) {
                "winnerConfig string must not contain comma, double-quote, or newline: $cs"
            }
        }

        Files.writeString(dir.resolve("walkforward_summary.csv"), renderSummaryCsv(result))
        Files.writeString(dir.resolve("walkforward_summary.json"), renderJson(result))
        Files.writeString(dir.resolve("concatenated_equity.csv"), renderEquityCsv(result.concatenatedTestCurve))
        Files.writeString(dir.resolve("winner_counts.csv"), renderWinnerCountsCsv(result.winnerCounts))

        val foldsDir = dir.resolve("folds")
        Files.createDirectories(foldsDir)
        for ((i, fold) in result.folds.withIndex()) {
            val padded = "fold_%03d".format(i + 1)
            val perFold = foldsDir.resolve(padded)
            Files.createDirectories(perFold)
            BacktestReportWriter(perFold).write(fold.testResult)
        }
    }

    private fun <C> renderSummaryCsv(result: WalkForwardResult<C>): String {
        val sb =
            StringBuilder(
                "foldIndex,trainStart,trainEnd,testStart,testEnd,winnerLabel,winnerConfig,trainScore,testTotalPnL,testMaxDrawdown\n",
            )
        for ((i, fold) in result.folds.withIndex()) {
            val r = fold.testResult.global
            sb
                .append(i + 1)
                .append(',')
                .append(fold.trainRange.from)
                .append(',')
                .append(fold.trainRange.to)
                .append(',')
                .append(fold.testRange.from)
                .append(',')
                .append(fold.testRange.to)
                .append(',')
                .append(fold.winnerLabel)
                .append(',')
                .append(fold.winnerConfig.toString())
                .append(',')
                .append(fold.trainScore.toPlainString())
                .append(',')
                .append(r.totalPnL.toPlainString())
                .append(',')
                .append(r.maxDrawdown.toPlainString())
                .append('\n')
        }
        return sb.toString()
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

    private fun renderWinnerCountsCsv(counts: Map<String, Int>): String {
        val sb = StringBuilder("configLabel,winCount\n")
        for ((label, count) in counts.entries.sortedByDescending { it.value }) {
            sb
                .append(label)
                .append(',')
                .append(count)
                .append('\n')
        }
        return sb.toString()
    }

    private fun <C> renderJson(result: WalkForwardResult<C>): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"folds\": [")
        if (result.folds.isNotEmpty()) {
            sb.append('\n')
            for ((i, fold) in result.folds.withIndex()) {
                sb.append("    ").append(renderFoldJson(i + 1, fold))
                if (i != result.folds.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("  ]")
        } else {
            sb.append("]")
        }
        sb.append(",\n  \"winnerCounts\": {")
        val entries = result.winnerCounts.entries.toList()
        for ((i, e) in entries.withIndex()) {
            sb
                .append("\n    ")
                .append(ReportSerializer.jsonString(e.key))
                .append(": ")
                .append(e.value)
            if (i != entries.size - 1) sb.append(",")
        }
        if (entries.isNotEmpty()) sb.append("\n  ")
        sb.append("},\n")
        sb.append("  \"meanTrainScore\": ").append(ReportSerializer.jsonBigDecimal(result.meanTrainScore)).append(",\n")
        sb.append("  \"meanTestScore\": ").append(ReportSerializer.jsonBigDecimal(result.meanTestScore)).append("\n")
        sb.append("}")
        return sb.toString()
    }

    private fun <C> renderFoldJson(
        index: Int,
        fold: WalkForwardFold<C>,
    ): String {
        val r = fold.testResult.global
        val sb = StringBuilder("{")
        sb.append("\n      \"foldIndex\": ").append(index).append(",")
        sb
            .append("\n      \"trainRange\": {\"from\": ")
            .append(ReportSerializer.jsonString(fold.trainRange.from.toString()))
            .append(", \"to\": ")
            .append(ReportSerializer.jsonString(fold.trainRange.to.toString()))
            .append("},")
        sb
            .append("\n      \"testRange\": {\"from\": ")
            .append(ReportSerializer.jsonString(fold.testRange.from.toString()))
            .append(", \"to\": ")
            .append(ReportSerializer.jsonString(fold.testRange.to.toString()))
            .append("},")
        sb.append("\n      \"winnerLabel\": ").append(ReportSerializer.jsonString(fold.winnerLabel)).append(",")
        sb
            .append(
                "\n      \"winnerConfig\": ",
            ).append(ReportSerializer.jsonString(fold.winnerConfig.toString()))
            .append(",")
        sb.append("\n      \"trainScore\": ").append(ReportSerializer.jsonBigDecimal(fold.trainScore)).append(",")
        sb.append("\n      \"testTotalPnL\": ").append(ReportSerializer.jsonBigDecimal(r.totalPnL)).append(",")
        sb.append("\n      \"testMaxDrawdown\": ").append(ReportSerializer.jsonBigDecimal(r.maxDrawdown)).append(",")
        sb.append("\n      \"topConfigs\": [")
        if (fold.topConfigs.isNotEmpty()) {
            sb.append('\n')
            for ((i, p) in fold.topConfigs.withIndex()) {
                sb
                    .append("        {\"label\": ")
                    .append(ReportSerializer.jsonString(p.first))
                    .append(", \"score\": ")
                    .append(ReportSerializer.jsonBigDecimal(p.second))
                    .append("}")
                if (i != fold.topConfigs.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("      ]")
        } else {
            sb.append("]")
        }
        sb.append("\n    }")
        return sb.toString()
    }
}
