package com.qkt.backtest

import java.math.BigDecimal

data class DrawdownPeriod(
    val peakTimestamp: Long,
    val peakEquity: BigDecimal,
    val troughTimestamp: Long,
    val troughEquity: BigDecimal,
    val recoveryTimestamp: Long?,
    val depthPct: BigDecimal,
    val durationMs: Long,
    val ongoing: Boolean,
)
