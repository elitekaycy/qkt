package com.qkt.backtest.walkforward

import com.qkt.backtest.EquitySample
import java.math.BigDecimal

data class WalkForwardResult<C>(
    val folds: List<WalkForwardFold<C>>,
    val winnerCounts: Map<String, Int>,
    val meanTrainScore: BigDecimal,
    val meanTestScore: BigDecimal,
    val concatenatedTestCurve: List<EquitySample>,
)
