package com.qkt.notify

import java.math.BigDecimal
import java.math.MathContext

/**
 * Per-strategy accumulator for the Telegram daily summary. Counts trades and risk
 * halts and tracks equity change since the previous summary.
 *
 * [snapshot] returns one strategy's totals and resets its window, so the daily-summary
 * producer reads it once per fire and the next window starts fresh. State is in-memory
 * — a daemon restart begins a fresh window.
 */
class DailyRollingTracker {
    private val trades = mutableMapOf<String, Int>()
    private val halts = mutableMapOf<String, Int>()
    private val equityBaseline = mutableMapOf<String, BigDecimal>()

    /** Record one filled trade for [strategyId]. */
    @Synchronized
    fun recordTrade(strategyId: String) {
        trades[strategyId] = (trades[strategyId] ?: 0) + 1
    }

    /** Record one risk halt for [strategyId]. */
    @Synchronized
    fun recordHalt(strategyId: String) {
        halts[strategyId] = (halts[strategyId] ?: 0) + 1
    }

    /**
     * Totals for [strategyId] since the previous snapshot; resets its window. The first
     * snapshot has no equity baseline and reports a zero delta; later snapshots report
     * the percent change in equity from the prior snapshot.
     */
    @Synchronized
    fun snapshot(
        strategyId: String,
        currentEquity: BigDecimal,
    ): DailyTotals {
        val tradesToday = trades.remove(strategyId) ?: 0
        val haltsToday = halts.remove(strategyId) ?: 0
        val baseline = equityBaseline[strategyId]
        val deltaPct =
            if (baseline == null || baseline.signum() == 0) {
                BigDecimal.ZERO
            } else {
                currentEquity
                    .subtract(baseline)
                    .divide(baseline, MathContext.DECIMAL64)
                    .multiply(BigDecimal(100))
            }
        equityBaseline[strategyId] = currentEquity
        return DailyTotals(tradesToday, haltsToday, deltaPct)
    }

    /** A strategy's accumulated totals for one daily-summary window. */
    data class DailyTotals(
        val tradesToday: Int,
        val haltsToday: Int,
        val equityDeltaPct: BigDecimal,
    )
}
