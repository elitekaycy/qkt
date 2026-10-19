package com.qkt.backtest

import com.qkt.positions.StrategyPositionTracker.LegAction
import java.math.BigDecimal

/**
 * One outcome per position, not per exit fill (#1452): a position split over several entry or exit fills
 * (partial fills, scale-outs) is one round trip. Each position's open quantity is tracked fill by fill and
 * its outcome is emitted when that quantity returns to zero; the outcome sums every fill's net P&L, so entry
 * costs are included and the outcomes add up to the realized total of closed positions. A position that had
 * exits but was still partly open at the end is emitted last, as of its last exit.
 *
 * A leg-routed fill (OPENED/CLOSED with a leg id) belongs to its leg. Anything else — a netted fill, or a
 * close naming a leg this tracker never saw open (a netted entry closed by a leg-routed stop) — belongs to
 * the strategy's position in that symbol; a netted exit larger than the position reverses it, closing this
 * round trip and opening the next with the excess.
 */
internal fun tradeOutcomes(trades: List<TradeRecord>): List<TradeOutcome> {
    val open = LinkedHashMap<Pair<String, String>, OpenRoundTrip>()
    val outcomes = ArrayList<TradeOutcome>()
    for (t in trades) {
        val qty = t.trade.quantity.abs()
        val legKey = t.legId?.let { t.strategyId to "leg:$it" }
        val key =
            when {
                legKey != null && t.legAction == LegAction.OPENED -> legKey
                legKey != null && t.legAction == LegAction.CLOSED && open.containsKey(legKey) -> legKey
                else -> t.strategyId to "symbol:${t.trade.symbol}"
            }
        val opening = if (key == legKey) t.legAction == LegAction.OPENED else !t.reducedExposure
        val trip = open[key]
        if (opening) {
            open.getOrPut(key) { OpenRoundTrip() }.add(qty, t.realized)
            continue
        }
        if (trip == null) {
            // An exit with no entry on record (the position predates the run): a round trip by itself.
            outcomes.add(TradeOutcome(t.trade.timestamp, t.realized))
            continue
        }
        trip.exit(qty, t.realized, t.trade.timestamp)
        if (trip.quantity.signum() > 0) continue
        open.remove(key)
        outcomes.add(TradeOutcome(t.trade.timestamp, trip.pnl))
        val reversedBy = trip.quantity.negate()
        if (key == legKey || reversedBy.signum() <= 0) continue
        open[key] = OpenRoundTrip().also { it.add(reversedBy, BigDecimal.ZERO) }
    }
    for (trip in open.values) {
        val lastExit = trip.lastExitMs ?: continue
        outcomes.add(TradeOutcome(lastExit, trip.pnl))
    }
    return outcomes
}

private class OpenRoundTrip {
    var quantity: BigDecimal = BigDecimal.ZERO
        private set
    var pnl: BigDecimal = BigDecimal.ZERO
        private set
    var lastExitMs: Long? = null
        private set

    fun add(
        qty: BigDecimal,
        realized: BigDecimal,
    ) {
        quantity = quantity.add(qty)
        pnl = pnl.add(realized)
    }

    fun exit(
        qty: BigDecimal,
        realized: BigDecimal,
        atMs: Long,
    ) {
        quantity = quantity.subtract(qty)
        pnl = pnl.add(realized)
        lastExitMs = atMs
    }
}
