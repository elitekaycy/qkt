package com.qkt.backtest.walkforward

import com.qkt.backtest.BacktestResult
import com.qkt.common.TimeRange
import java.math.BigDecimal

data class WalkForwardFold<C>(
    val trainRange: TimeRange,
    val testRange: TimeRange,
    val winnerLabel: String,
    val winnerConfig: C,
    val trainScore: BigDecimal,
    val testResult: BacktestResult,
    val topConfigs: List<Pair<String, BigDecimal>>,
)
