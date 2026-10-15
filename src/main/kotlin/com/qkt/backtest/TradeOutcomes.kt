package com.qkt.backtest

import java.math.BigDecimal

/**
 * One outcome per exit fill. An entry fill carries only its own cost (commission), and the trade is not complete
 * until the position exits, so that cost is folded into the first exit outcome after it rather than dropped: a
 * round trip's win/loss, profit factor and Monte Carlo sample then include both fills' costs and their sum equals
 * the realized total. A leg-routed entry is matched to its leg's exit; an entry without a leg id (a netted
 * position, e.g. a plain BUY then SELL, or the opening half of a reversal) to the next exit of the same strategy
 * and symbol.
 */
internal fun tradeOutcomes(trades: List<TradeRecord>): List<BigDecimal> {
    val byLeg = HashMap<Pair<String, String>, BigDecimal>()
    val bySymbol = HashMap<Pair<String, String>, BigDecimal>()
    val outcomes = ArrayList<BigDecimal>()
    for (t in trades) {
        val leg = t.legId
        val symbol = t.strategyId to t.trade.symbol
        if (!t.reducedExposure) {
            if (leg != null) byLeg.merge(t.strategyId to leg, t.realized, BigDecimal::add)
            if (leg == null) bySymbol.merge(symbol, t.realized, BigDecimal::add)
            continue
        }
        val carried =
            (leg?.let { byLeg.remove(t.strategyId to it) } ?: BigDecimal.ZERO).add(
                bySymbol.remove(symbol) ?: BigDecimal.ZERO,
            )
        outcomes.add(t.realized.add(carried))
    }
    return outcomes
}
