package com.qkt.backtest

import java.math.BigDecimal

data class PerformanceReport(
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    val winRate: BigDecimal,
    val maxDrawdown: BigDecimal,
    val profitFactor: BigDecimal?,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val largestWin: BigDecimal,
    val largestLoss: BigDecimal,
    val maxConsecutiveLosses: Int,
    val sharpeRatio: BigDecimal?,
    val calmarRatio: BigDecimal?,
    val equityCurve: List<EquitySample>,
    val drawdownPeriods: List<DrawdownPeriod> = emptyList(),
    val monteCarlo: MonteCarloSummary? = null,
    /**
     * Total commission charged over the run (#335). The realized/total PnL above are already
     * net of this; it is reported separately as the bridge from gross trade PnL to net —
     * `gross = totalPnL + commissionPaid`. Zero when no commission was configured.
     */
    val commissionPaid: BigDecimal = BigDecimal.ZERO,
)
