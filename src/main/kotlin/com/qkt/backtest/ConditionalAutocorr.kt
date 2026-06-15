package com.qkt.backtest

import java.math.BigDecimal

/**
 * Volatility regime of a bar, split by its absolute return against the run's median absolute
 * return: [HIGH] is `|return| >= median`, [LOW] is below. A two-level proxy for "was this a
 * busy bar or a quiet one", used to test whether short-horizon continuation concentrates in
 * volatile bars.
 */
enum class Regime { HIGH, LOW }

/**
 * Lag-1 autocorrelation of per-bar close-to-close returns for one symbol, bucketed by the
 * conditions under which the return occurred.
 *
 * Lag-1 autocorrelation measures whether a bar's return predicts the next bar's return: near
 * `+1` = trending (a move tends to continue), near `-1` = mean-reverting (a move tends to
 * reverse), near `0` = no relationship. This is a property of the market data, identical across
 * strategies, so it is computed once per backtest rather than per strategy.
 *
 * Two bucketings of the same returns:
 * - [perHour]: keyed by UTC hour-of-day `0..23` — does continuation cluster in certain hours?
 * - [perRegime]: keyed by [Regime] — does it cluster in high-volatility bars?
 *
 * Sparse by design: a bucket with fewer than three returns, or with zero return variance (all
 * returns identical), is omitted rather than reported as a meaningless or `NaN` value. The
 * parallel [hourCounts] / [regimeCounts] give the sample size behind each populated bucket.
 *
 * The regime split point (the median absolute return) is an approximate streaming estimate, so
 * the high/low partition is near — not exactly — 50/50; the autocorrelation values themselves
 * are exact.
 *
 * e.g. an hour whose returns alternate `+0.01, -0.01, +0.01, ...` reports `perHour[h] ≈ -1.0`;
 * an hour whose returns are all `+0.01` is omitted (zero variance), not reported as `+1.0`.
 */
data class ConditionalAutocorr(
    val perHour: Map<Int, BigDecimal>,
    val perRegime: Map<Regime, BigDecimal>,
    val hourCounts: Map<Int, Int>,
    val regimeCounts: Map<Regime, Int>,
)
