package com.qkt.backtest.report

import java.math.BigDecimal

/**
 * Tuning knobs for [HtmlReportWriter] — caps on per-table row counts and parameters
 * for the Monte-Carlo bootstrap visualization. Defaults match the standard report
 * we ship; override individual fields with the data class `copy(...)`.
 */
data class HtmlReportConfig(
    /** Number of oldest trades shown at the top of the trades table. */
    val tradeTableHead: Int = 200,
    /** Number of most recent trades shown at the bottom of the trades table. */
    val tradeTableTail: Int = 200,
    /** Drawdown threshold (negative percentage) below which a period is annotated. */
    val drawdownThresholdPct: BigDecimal = BigDecimal("-0.01"),
    /** Number of bootstrap-resample iterations used to draw the Monte-Carlo equity fan. */
    val monteCarloSimulations: Int = 1000,
    /** Seed for the Monte-Carlo bootstrap so reports are reproducible across runs. */
    val monteCarloSeed: Long = 42L,
    /** Below this trade count the Monte-Carlo section is suppressed (not statistically meaningful). */
    val minTradesForMonteCarlo: Int = 30,
)
