package com.qkt.cli.experiment

import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.ParamGrid
import com.qkt.cli.RankMetric
import com.qkt.cli.ResearchGovernance
import com.qkt.evidence.EvidenceJson

/** `train_summary.json` / `validation_summary.json`: the split's ranked runs with params and metrics. */
internal fun stageSummaryJson(
    split: String,
    rank: RankMetric,
    ranked: List<SweepRun<ParamGrid.Combo>>,
): String =
    buildString {
        append("{\"split\":").append(EvidenceJson.jsonString(split))
        append(",\"rank\":").append(EvidenceJson.jsonString(rank.flag))
        append(",\"trialCount\":").append(ranked.size)
        append(",\"runs\":[")
        append(ranked.joinToString(",") { runJson(it, rank) })
        append("]}")
    }

/** A JSON array of ranked runs, in rank order. */
internal fun runsJson(
    ranked: List<SweepRun<ParamGrid.Combo>>,
    rank: RankMetric,
): String = ranked.joinToString(",", prefix = "[", postfix = "]") { runJson(it, rank) }

private fun runJson(
    run: SweepRun<ParamGrid.Combo>,
    rank: RankMetric,
): String =
    buildString {
        append("{\"label\":").append(EvidenceJson.jsonString(run.label))
        append(",\"params\":").append(ResearchGovernance.stringMapJson(run.config.overrides))
        append(",\"metrics\":").append(ResearchGovernance.runMetricsJson(run.result.global, rank))
        append("}")
    }
