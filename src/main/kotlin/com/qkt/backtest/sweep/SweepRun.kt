package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult

data class SweepRun<C>(
    val label: String,
    val config: C,
    val result: BacktestResult,
)
