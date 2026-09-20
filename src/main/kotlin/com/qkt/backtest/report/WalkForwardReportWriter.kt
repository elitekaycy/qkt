package com.qkt.backtest.report

import com.qkt.backtest.walkforward.WalkForwardResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a walk-forward analysis result ([com.qkt.backtest.walkforward.WalkForwardResult])
 * to a directory: one row per fold in `walkforward_summary.csv`, an aggregated
 * stitched-equity CSV, a JSON summary, and a full [BacktestReportWriter] bundle per fold
 * under `folds/fold_NNN/`. One writer per output directory; call [write] once.
 *
 * Winner labels and configs are written verbatim; CSV fields containing a comma, quote,
 * or newline are RFC 4180 quoted so a `fast=8,slow=20` label round-trips through any
 * CSV reader. Fold directories are indexed, never named after a label.
 *
 */
class WalkForwardReportWriter(
    private val dir: Path,
) {
    /**
     * Emit all artifacts for [result] into the writer's directory. Overwrites any
     * existing files; the directory must exist and be writable.
     *
     * @param configText renders a winner config for the summary CSV/JSON; defaults to `toString()`.
     */
    fun <C> write(
        result: WalkForwardResult<C>,
        configText: (C) -> String = { it.toString() },
    ) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }

        Files.writeString(dir.resolve("walkforward_summary.csv"), renderSummaryCsv(result, configText))
        Files.writeString(dir.resolve("walkforward_summary.json"), WalkForwardSummaryJson.render(result, configText))
        Files.writeString(dir.resolve("concatenated_equity.csv"), EquityCsv.render(result.concatenatedTestCurve))
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

    private fun <C> renderSummaryCsv(
        result: WalkForwardResult<C>,
        configText: (C) -> String,
    ): String {
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
                .append(csvField(fold.winnerLabel))
                .append(',')
                .append(csvField(configText(fold.winnerConfig)))
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

    private fun renderWinnerCountsCsv(counts: Map<String, Int>): String {
        val sb = StringBuilder("configLabel,winCount\n")
        for ((label, count) in counts.entries.sortedByDescending { it.value }) {
            sb
                .append(csvField(label))
                .append(',')
                .append(count)
                .append('\n')
        }
        return sb.toString()
    }
}
