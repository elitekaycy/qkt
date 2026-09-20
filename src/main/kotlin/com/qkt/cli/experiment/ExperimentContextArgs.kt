package com.qkt.cli.experiment

import com.qkt.cli.Args
import com.qkt.cli.ExperimentPlan
import java.nio.file.Path

/**
 * Synthesizes the `qkt backtest` arguments that provision one context spanning train start to
 * test end, forwarding the data, broker and fill flags the experiment was invoked with.
 */
internal fun experimentContextArgs(
    args: Args,
    strategyPath: Path,
    plan: ExperimentPlan,
    datasetPath: String?,
): Args {
    val fullFrom = plan.splits.train.from
    val fullTo = plan.splits.test.to
    val tokens =
        mutableListOf(
            "backtest",
            strategyPath.toString(),
            "--from",
            fullFrom.toString(),
            "--to",
            fullTo.toString(),
        )

    fun passOption(name: String) {
        args.option(name)?.let {
            tokens.add("--$name")
            tokens.add(it)
        }
    }
    for (name in listOf("data-root", "config", "broker", "instruments", "starting-balance")) {
        passOption(name)
    }
    datasetPath?.let {
        tokens.add("--dataset")
        tokens.add(it)
    }
    for (flag in listOf("bars", "no-fetch", "allow-incomplete")) {
        if (args.flag(flag)) tokens.add("--$flag")
    }
    return Args(tokens.toTypedArray())
}
