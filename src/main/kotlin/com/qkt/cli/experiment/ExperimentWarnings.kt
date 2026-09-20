package com.qkt.cli.experiment

import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.ExperimentPlan
import com.qkt.cli.ParamGrid
import com.qkt.cli.RankMetric
import com.qkt.cli.ResearchGovernance

/** Research-governance warnings for a run: search size, an unstable winning neighborhood, an unpinned dataset. */
internal fun experimentWarnings(
    plan: ExperimentPlan,
    trialCount: Int,
    trainRanked: List<SweepRun<ParamGrid.Combo>>,
    rank: RankMetric,
    datasetPath: String?,
): List<String> {
    val unstableWarning =
        ResearchGovernance.unstableNeighborhoodWarning(
            trainRanked,
            plan.parameterGrid,
            rank,
        )
    val unstableWarnings = if (unstableWarning != null) listOf(unstableWarning) else emptyList()
    val datasetWarnings =
        if (datasetPath == null) {
            listOf("experiment plan did not pin a dataset snapshot; reproducibility is weaker.")
        } else {
            emptyList()
        }
    return ResearchGovernance.largeSearchWarnings(trialCount, plan.selection.largeSearchThreshold) +
        unstableWarnings +
        datasetWarnings
}
