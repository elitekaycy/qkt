package com.qkt.backtest

import java.math.BigDecimal

data class EquitySample(
    val timestamp: Long,
    val equity: BigDecimal,
)
