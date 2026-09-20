package com.qkt.cli.sweep

import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.CliEvidenceJson
import com.qkt.cli.RankMetric
import com.qkt.cli.ResearchGovernance
import com.qkt.cli.ScenarioSpec
import com.qkt.evidence.DatasetEvidence

/**
 * Prints the ranked sweep as a JSON array: one row per scenario with params, metrics, daily PnL,
 * per-day fill cost inputs, provenance, warnings and the pinned dataset when there is one.
 */
internal fun printSweepJson(
    ranked: List<SweepRun<ScenarioSpec>>,
    rank: RankMetric,
    dataset: DatasetEvidence,
    warnings: List<String>,
) {
    val datasetJson = CliEvidenceJson.pinnedDataset(dataset)
    val provenanceJson = ResearchGovernance.metricProvenanceJson("sweep", rank, ranked.size)
    val warningsJson = ResearchGovernance.warningListJson(warnings)
    val rows =
        ranked.joinToString(",") { run ->
            val r = run.result.global
            val params =
                run.config.params.entries
                    .joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            val daily =
                r.dailyPnL.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" }
            val fillCosts = fillCostSummaryJson(run.result.trades)
            val datasetField = datasetJson?.let { ""","dataset":$it""" } ?: ""
            """{"label":"${run.label}","params":{$params},"rank":"${rank.flag}",""" +
                """"trialCount":${ranked.size},"metricProvenance":$provenanceJson,""" +
                """"selectionWarnings":$warningsJson,""" +
                """"trades":${r.tradeCount},"totalPnL":${r.totalPnL.toPlainString()},""" +
                """"commissionPaid":${r.commissionPaid.toPlainString()},""" +
                """"sharpe":${r.sharpeRatio?.toPlainString() ?: "null"},""" +
                """"calmar":${r.calmarRatio?.toPlainString() ?: "null"},""" +
                """"maxDrawdown":${r.maxDrawdown.toPlainString()},"winRate":${r.winRate.toPlainString()},""" +
                """"maxDailyDrawdown":${r.maxDailyDrawdown.toPlainString()},"dailyPnL":{$daily},""" +
                """"fillCostSummary":$fillCosts$datasetField}"""
        }
    println("[$rows]")
}
