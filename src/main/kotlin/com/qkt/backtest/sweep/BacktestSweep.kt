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

    fun run(): SweepResult<C> = error("not yet implemented; see Tasks 4 + 5")
}
