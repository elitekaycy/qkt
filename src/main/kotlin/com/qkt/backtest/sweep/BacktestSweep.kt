package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest

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

    private fun runParallel(): SweepResult<C> = error("parallel mode lands in Task 5")
}
