package com.qkt.backtest.sweep

import com.qkt.marketdata.TickFeed
import com.qkt.research.ReplayEngine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a grid sweep by decoding the market stream once per worker and fanning each tick out to one
 * [ReplayEngine] per combo. Every engine owns isolated execution state (broker, P&L, risk, candle
 * aggregation); only the decoded tick stream is shared. Results are bit-identical to running each
 * combo as its own backtest (see SweepReplayParityTest): every engine sees the same ticks in the same
 * order, and a tick pushed via [ReplayEngine.ingest] takes the identical path it takes when pulled.
 *
 * Combos are partitioned round-robin across [parallelism] workers; each worker decodes its own copy
 * of the feed once and drives its subset, so the feed is decoded `parallelism` times, not once per
 * combo. Output preserves input (config) order; ranking happens afterward via [SweepResult.rankedBy].
 */
class SweepReplay<C>(
    private val configs: List<Pair<String, C>>,
    private val sharedFeed: () -> TickFeed,
    private val engineFor: (label: String, config: C) -> ReplayEngine,
    private val parallelism: Int = 1,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) {
            "config labels must be unique: ${configs.map { it.first }}"
        }
        require(configs.all { it.first.isNotBlank() }) { "config labels must be non-blank" }
    }

    fun run(): SweepResult<C> {
        val groups = partition(configs, parallelism.coerceAtMost(configs.size))
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
        return SweepResult(configs.map { byLabel.getValue(it.first) })
    }

    /**
     * One worker: build this group's engines, decode the shared feed once, and fan every tick to all
     * of them in a fixed order (deterministic), then snapshot each. A combo that throws aborts the
     * sweep — matching [BacktestSweep]'s fail-fast contract.
     */
    private fun runGroup(group: List<Pair<String, C>>): List<SweepRun<C>> {
        val engines = group.map { (label, cfg) -> Triple(label, cfg, engineFor(label, cfg)) }
        val feed = sharedFeed()
        try {
            while (true) {
                val tick = feed.next() ?: break
                for ((_, _, engine) in engines) engine.ingest(tick)
            }
        } finally {
            feed.close()
        }
        return engines.map { (label, cfg, engine) -> SweepRun(label, cfg, engine.snapshot()) }
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
