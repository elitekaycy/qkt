package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a grid as multi-strategy backtests: combos are partitioned across [parallelism] groups, and
 * each group runs as ONE backtest whose strategies are that group's combos sharing a single decoded
 * and aggregated feed. So within a group the dominant per-tick work — tick decode, candle aggregation,
 * price tracking — happens once for all its combos instead of once per combo, and the groups run on
 * separate cores. Each combo's result is split back out from the per-strategy reports.
 *
 * Bit-identical to running each combo as its own backtest on the sweep metrics (see
 * MultiStrategySweepParityTest), as long as combos do not couple through shared account state — the
 * caller guarantees that by only using this path when no account-protection halts are configured
 * (`BacktestContext.hasAccountHalts` is false). Output preserves input (combo) order; ranking happens
 * afterward via [SweepResult.rankedBy].
 */
class MultiStrategySweep<C>(
    private val combos: List<Pair<String, C>>,
    private val overridesOf: (C) -> Map<String, String>,
    private val backtestFor: (List<Pair<String, Map<String, String>>>) -> Backtest,
    private val parallelism: Int = 1,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
        require(combos.isNotEmpty()) { "combos must not be empty" }
        require(combos.map { it.first }.toSet().size == combos.size) {
            "combo labels must be unique: ${combos.map { it.first }}"
        }
    }

    fun run(): SweepResult<C> {
        val groups = partition(combos, parallelism.coerceAtMost(combos.size))
        val all: List<SweepRun<C>> =
            if (groups.size == 1) {
                runGroup(groups.single())
            } else {
                val executor = Executors.newFixedThreadPool(groups.size)
                try {
                    val futures = groups.map { g -> executor.submit<List<SweepRun<C>>> { runGroup(g) } }
                    try {
                        futures.flatMap { it.get() }
                    } catch (e: ExecutionException) {
                        executor.shutdownNow()
                        throw e.cause ?: e
                    }
                } finally {
                    executor.shutdown()
                    executor.awaitTermination(1, TimeUnit.MINUTES)
                }
            }
        // Re-key to the input order so the result is independent of how combos were partitioned.
        val byLabel = all.associateBy { it.label }
        return SweepResult(combos.map { byLabel.getValue(it.first) })
    }

    /** One group: run all its combos as strategies in a single backtest, then split per-strategy. */
    private fun runGroup(group: List<Pair<String, C>>): List<SweepRun<C>> {
        val labeled = group.map { (label, c) -> label to overridesOf(c) }
        val result = backtestFor(labeled).run()
        return group.map { (label, c) ->
            val report = result.perStrategy.getValue(label)
            SweepRun(
                label,
                c,
                result.copy(
                    global = report,
                    perStrategy = mapOf(label to report),
                    trades = result.trades.filter { it.strategyId == label },
                ),
            )
        }
    }

    private fun <T> partition(
        items: List<T>,
        n: Int,
    ): List<List<T>> {
        val out = MutableList(n) { mutableListOf<T>() }
        items.forEachIndexed { i, item -> out[i % n].add(item) }
        return out
    }
}
