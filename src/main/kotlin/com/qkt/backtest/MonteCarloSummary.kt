package com.qkt.backtest

import java.math.BigDecimal

data class EquityFanPoint(
    val tradeIndex: Int,
    val p5: BigDecimal,
    val p25: BigDecimal,
    val p50: BigDecimal,
    val p75: BigDecimal,
    val p95: BigDecimal,
)

data class MonteCarloSummary(
    val simulations: Int,
    val finalEquityP5: BigDecimal,
    val finalEquityP25: BigDecimal,
    val finalEquityP50: BigDecimal,
    val finalEquityP75: BigDecimal,
    val finalEquityP95: BigDecimal,
    val maxDrawdownP5: BigDecimal,
    val maxDrawdownP95: BigDecimal,
    val probabilityNegativeFinal: BigDecimal,
    val equityFanByTradeIndex: List<EquityFanPoint>,
)
