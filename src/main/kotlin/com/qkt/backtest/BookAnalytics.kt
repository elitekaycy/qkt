package com.qkt.backtest

import java.math.BigDecimal

/** Pearson correlation of two strategies' per-sample return series over the run. */
data class CorrelationPair(
    val a: String,
    val b: String,
    val correlation: BigDecimal,
)

/**
 * Cross-strategy ("book") analytics for a portfolio backtest — the relationships the per-strategy
 * reports cannot show on their own. Null on a single-strategy run.
 *
 * - [contributionToReturn]: each strategy's share of book total PnL (sums to 1; a strategy can be
 *   negative or above 1 when strategies offset each other).
 * - [returnCorrelation]: pairwise return correlation — the diversification picture. e.g. two trend
 *   strategies at +0.9 are nearly the same bet, so the book is less diversified than it looks.
 * - [riskContribution]: each strategy's percent contribution to book return variance (PCTR); sums
 *   to ~1. A strategy with a small return share but a large risk share is eating the risk budget.
 * - [drawdownContribution]: each strategy's share of the book's worst peak-to-trough drawdown.
 */
data class BookAnalytics(
    val contributionToReturn: Map<String, BigDecimal>,
    val returnCorrelation: List<CorrelationPair>,
    val riskContribution: Map<String, BigDecimal>,
    val drawdownContribution: Map<String, BigDecimal>,
)
