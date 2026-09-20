package com.qkt.cli.sweep

import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.Args
import com.qkt.cli.ParamGrid
import com.qkt.cli.RankMetric
import com.qkt.cli.ResearchGovernance
import com.qkt.cli.ScenarioSpec

/**
 * Selection warnings for a sweep: a search larger than `--large-search-threshold`, and for grid
 * sweeps (not `--scenarios` files) a winner whose parameter neighborhood is unstable.
 */
internal fun sweepWarnings(
    args: Args,
    ranked: List<SweepRun<ScenarioSpec>>,
    axes: Map<String, List<String>>,
    rank: RankMetric,
    trialCount: Int,
): List<String> {
    val largeSearchThreshold =
        args.option("large-search-threshold")?.toIntOrNull()
            ?: ResearchGovernance.DEFAULT_LARGE_SEARCH_THRESHOLD
    val unstableWarning =
        if (args.option("scenarios") == null) {
            val gridRuns =
                ranked.map { run ->
                    SweepRun(
                        label = run.label,
                        config = ParamGrid.Combo(run.label, run.config.params),
                        result = run.result,
                    )
                }
            ResearchGovernance.unstableNeighborhoodWarning(
                ranked = gridRuns,
                axes = axes,
                rank = rank,
            )
        } else {
            null
        }
    val unstableWarnings = if (unstableWarning != null) listOf(unstableWarning) else emptyList()
    return ResearchGovernance.largeSearchWarnings(
        trialCount = trialCount,
        threshold = largeSearchThreshold,
    ) + unstableWarnings
}
