package com.qkt.cli.experiment

import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.backtest.sweep.SweepReplay
import com.qkt.backtest.sweep.SweepRun
import com.qkt.cli.BacktestContext
import com.qkt.cli.ParamGrid
import com.qkt.cli.RankMetric
import com.qkt.common.TimeRange
import com.qkt.marketdata.source.SequenceTickFeed

/** Runs every combo over [range] and returns the runs ranked best-first by [rank]. */
internal fun runExperimentSweep(
    ctx: BacktestContext,
    combos: List<ParamGrid.Combo>,
    range: TimeRange,
    rank: RankMetric,
    parallelism: Int,
): List<SweepRun<ParamGrid.Combo>> =
    // Plain-bars fills synthesize each bar's extremes adverse-first for the open position,
    // which differs per combo — a shared tick stream cannot serve all engines, so bars sweeps
    // run per-combo (bar decode is cheap and page-cached).
    if (ctx.barFills) {
        BacktestSweep(
            configs = combos.map { it.label to it },
            backtestFactory = { _, combo -> ctx.backtest(combo.overrides, range) },
            parallelism = parallelism,
        ).run().rankedBy { rank.score(it) }
    } else {
        SweepReplay(
            configs = combos.map { it.label to it },
            sharedFeed = { ctx.backtest(emptyMap(), range).detachFeed() },
            engineFor = { _, combo ->
                ctx.backtest(combo.overrides, range).toEngine(SequenceTickFeed(emptySequence()))
            },
            parallelism = parallelism,
        ).run().rankedBy { rank.score(it) }
    }
