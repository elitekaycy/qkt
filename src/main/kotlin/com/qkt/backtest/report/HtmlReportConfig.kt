package com.qkt.backtest.report

import java.math.BigDecimal

data class HtmlReportConfig(
    val tradeTableHead: Int = 200,
    val tradeTableTail: Int = 200,
    val drawdownThresholdPct: BigDecimal = BigDecimal("-0.01"),
    val monteCarloSimulations: Int = 1000,
    val monteCarloSeed: Long = 42L,
    val minTradesForMonteCarlo: Int = 30,
)
