package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BacktestSweep<C>(
    private val configs: List<Pair<String, C>>,
    private val backtestFactory: (label: String, config: C) -> Backtest,
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

    fun run(): SweepResult<C> =
        if (parallelism == 1) runSequential() else runParallel()

    private fun runSequential(): SweepResult<C> =
        SweepResult(
            configs.map { (label, config) ->
                SweepRun(label, config, backtestFactory(label, config).run())
            },
        )

    private fun runParallel(): SweepResult<C> {
        val poolSize = parallelism.coerceAtMost(configs.size)
        val executor = Executors.newFixedThreadPool(poolSize)
        try {
            val futures =
                configs.map { (label, config) ->
                    executor.submit<SweepRun<C>> {
                        SweepRun(label, config, backtestFactory(label, config).run())
                    }
                }
            return try {
                SweepResult(futures.map { it.get() })
            } catch (e: ExecutionException) {
                executor.shutdownNow()
                throw e.cause ?: e
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.MINUTES)
        }
    }
}
