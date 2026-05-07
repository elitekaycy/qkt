package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult
import java.math.BigDecimal

data class SweepResult<C>(
    val runs: List<SweepRun<C>>,
) {
    fun byLabel(label: String): SweepRun<C>? = runs.firstOrNull { it.label == label }

    fun rankedBy(scoreOf: (BacktestResult) -> BigDecimal): List<SweepRun<C>> =
        runs.sortedByDescending { scoreOf(it.result) }
}
