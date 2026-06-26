package com.qkt.cli

import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.sweep.SweepRun
import com.qkt.evidence.EvidenceJson
import java.math.BigDecimal

object ResearchGovernance {
    const val DEFAULT_LARGE_SEARCH_THRESHOLD: Int = 100

    fun largeSearchWarnings(
        trialCount: Int,
        threshold: Int = DEFAULT_LARGE_SEARCH_THRESHOLD,
    ): List<String> =
        if (trialCount > threshold) {
            listOf(
                "$trialCount configurations were evaluated; treat the selected metric as " +
                    "multiple-comparison exposed until it survives validation and test splits.",
            )
        } else {
            emptyList()
        }

    fun metricProvenanceJson(
        source: String,
        rank: RankMetric,
        trialCount: Int,
    ): String =
        """{"selectedMetric":${EvidenceJson.jsonString(rank.flag)},"source":${EvidenceJson.jsonString(source)},""" +
            """"trialCount":$trialCount,"ranking":"descending"}"""

    fun warningListJson(warnings: List<String>): String =
        warnings.joinToString(",", prefix = "[", postfix = "]") { EvidenceJson.jsonString(it) }

    fun stringMapJson(values: Map<String, String>): String =
        values.entries
            .sortedBy { it.key }
            .joinToString(",", prefix = "{", postfix = "}") {
                "${EvidenceJson.jsonString(it.key)}:${EvidenceJson.jsonString(it.value)}"
            }

    fun stringListJson(values: List<String>): String =
        values.joinToString(",", prefix = "[", postfix = "]") { EvidenceJson.jsonString(it) }

    fun unstableNeighborhoodWarning(
        ranked: List<SweepRun<ParamGrid.Combo>>,
        axes: Map<String, List<String>>,
        rank: RankMetric,
    ): String? {
        if (ranked.size < 3 || axes.isEmpty()) return null
        val winner = ranked.firstOrNull() ?: return null
        val winnerScore = rank.valueOf(winner.result.global) ?: return null
        if (winnerScore.signum() <= 0) return null
        val byParams = ranked.associateBy { it.config.overrides }
        val unstableNeighbors =
            neighboringParamSets(winner.config.overrides, axes)
                .mapNotNull { params ->
                    val score = byParams[params]?.result?.global?.let(rank::valueOf)
                    if (score == null || score < winnerScore.multiply(BigDecimal("0.50"))) {
                        params
                    } else {
                        null
                    }
                }
        if (unstableNeighbors.isEmpty()) return null
        return "selected params ${winner.config.overrides} have ${unstableNeighbors.size} adjacent grid " +
            "neighbor(s) below half the winning ${rank.flag}; treat this as unstable-neighborhood risk."
    }

    fun runMetricsJson(
        report: PerformanceReport,
        rank: RankMetric,
    ): String =
        buildString {
            append("{\"rankScore\":").append(rank.valueOf(report)?.toPlainString() ?: "null")
            append(",\"trades\":").append(report.tradeCount)
            append(",\"totalPnL\":").append(report.totalPnL.toPlainString())
            append(",\"sharpe\":").append(report.sharpeRatio?.toPlainString() ?: "null")
            append(",\"calmar\":").append(report.calmarRatio?.toPlainString() ?: "null")
            append(",\"maxDrawdown\":").append(report.maxDrawdown.toPlainString())
            append(",\"winRate\":").append(report.winRate.toPlainString())
            append("}")
        }

    private fun neighboringParamSets(
        selected: Map<String, String>,
        axes: Map<String, List<String>>,
    ): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        for ((axis, values) in axes) {
            val current = selected[axis] ?: continue
            val index = values.indexOf(current)
            if (index < 0) continue
            for (neighborIndex in listOf(index - 1, index + 1)) {
                if (neighborIndex in values.indices) {
                    out.add(selected + (axis to values[neighborIndex]))
                }
            }
        }
        return out
    }
}
