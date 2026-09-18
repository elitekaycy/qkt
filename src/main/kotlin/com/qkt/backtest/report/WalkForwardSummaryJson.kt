package com.qkt.backtest.report

import com.qkt.backtest.walkforward.WalkForwardFold
import com.qkt.backtest.walkforward.WalkForwardResult

/**
 * The `walkforward_summary.json` artifact: one object per fold (train/test ranges, winner, scores,
 * top configs), the winner counts, and the mean train and test scores.
 */
internal object WalkForwardSummaryJson {
    fun <C> render(
        result: WalkForwardResult<C>,
        configText: (C) -> String,
    ): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"folds\": [")
        if (result.folds.isNotEmpty()) {
            sb.append('\n')
            for ((i, fold) in result.folds.withIndex()) {
                sb.append("    ").append(renderFoldJson(i + 1, fold, configText))
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
        configText: (C) -> String,
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
            ).append(ReportSerializer.jsonString(configText(fold.winnerConfig)))
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
