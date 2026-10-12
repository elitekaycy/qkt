package com.qkt.backtest

import java.math.BigDecimal

/**
 * One outcome per exit fill. A leg-routed entry fill carries only its own cost (commission), and the trade is
 * not complete until the leg exits, so that cost is folded into the leg's first exit outcome rather than
 * dropped: a round trip's win/loss, profit factor and Monte Carlo sample then include both fills' costs and
 * their sum equals the realized total. Fills without a leg id keep the exit-only outcome (nothing to match).
 */
internal fun tradeOutcomes(trades: List<TradeRecord>): List<BigDecimal> {
    val entryCost = HashMap<Pair<String, String>, BigDecimal>()
    val outcomes = ArrayList<BigDecimal>()
    for (t in trades) {
        val leg = t.legId
        if (!t.reducedExposure) {
            if (leg != null) entryCost.merge(t.strategyId to leg, t.realized, BigDecimal::add)
            continue
        }
        val carried = if (leg != null) entryCost.remove(t.strategyId to leg) else null
        outcomes.add(if (carried == null) t.realized else t.realized.add(carried))
    }
    return outcomes
}
