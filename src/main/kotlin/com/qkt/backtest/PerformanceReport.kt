package com.qkt.backtest

import java.math.BigDecimal

data class PerformanceReport(
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    /**
     * Fraction of *decided* trades that were profitable. Break-even (zero-PnL) trades are
     * excluded from the denominator, so this is not wins/total — e.g. 12 wins of 24 decided
     * trades is 0.50 even if 3 more closed flat. Zero when no trades closed.
     */
    val winRate: BigDecimal,
    val maxDrawdown: BigDecimal,
    val profitFactor: BigDecimal?,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val largestWin: BigDecimal,
    val largestLoss: BigDecimal,
    val maxConsecutiveLosses: Int,
    /**
     * Annualized Sharpe ratio, risk-free rate 0, computed from equity-curve returns. The
     * annualization factor is *inferred from the average sample spacing* in TICK mode, so very
     * short or sparse runs can distort it. Null when undefined (fewer than two samples, or zero
     * return variance).
     */
    val sharpeRatio: BigDecimal?,
    /**
     * Calmar ratio = **total** return / max drawdown. Uses the total (not annualized) return, so
     * it reads high on short backtests; compare across runs of equal length. Null when max
     * drawdown is zero.
     */
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
    /**
     * Realized PnL bucketed by UTC day (#348) — `{ 2026-06-04: +120.50, ... }`. Empty when no
     * trades closed. Sums each trade's realized PnL into the day of its fill timestamp.
     */
    val dailyPnL: Map<java.time.LocalDate, BigDecimal> = emptyMap(),
    /**
     * Worst single-day intraday equity drawdown over the run (#348), as a fraction of that day's
     * opening equity — the figure to size against a prop-firm daily limit. Zero when equity never
     * fell intraday. Distinct from [maxDrawdown], which is the peak-to-trough over the whole run.
     */
    val maxDailyDrawdown: BigDecimal = BigDecimal.ZERO,
)
