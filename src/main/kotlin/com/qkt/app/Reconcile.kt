package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.Position
import java.math.BigDecimal

/** Bare symbol without the broker prefix: "EXNESS:XAUUSD" -> "XAUUSD"; an already-bare key is returned unchanged. */
private fun bareSymbol(symbol: String): String = symbol.substringAfter(":")

private fun signedQty(ticket: BrokerPositionTicket): BigDecimal =
    if (ticket.side == Side.BUY) ticket.qty else ticket.qty.negate()

private fun netBySymbol(tickets: List<BrokerPositionTicket>): Map<String, BigDecimal> =
    tickets
        .groupBy { bareSymbol(it.symbol) }
        .mapValues { (_, ts) -> ts.fold(BigDecimal.ZERO) { acc, t -> acc + signedQty(t) } }

/**
 * Engine-vs-broker net-position deltas for one strategy on a (possibly shared) account.
 *
 * Both sides are netted per BARE symbol so the broker's prefixed key (e.g. "EXNESS:XAUUSD")
 * and the engine tracker's bare key ("XAUUSD") line up instead of double-counting one
 * position into two phantom deltas. The broker side is scoped to the venue tickets this
 * strategy owns by [attribution] — on a shared account another strategy's legs are not this
 * strategy's drift. Any venue ticket owned by no live strategy surfaces under an
 * "unattributed:<symbol>" row so a real orphan is never silently hidden.
 *
 * e.g. broker BUY 0.25 + SELL 0.24 + SELL 0.14 (all owned here) net to -0.13; an engine net
 * of -0.13 yields no delta (clean), while an engine net of +0.25 yields one real delta.
 */
fun reconcileDeltas(
    ownerId: String,
    brokerTickets: List<BrokerPositionTicket>,
    attribution: TicketAttribution,
    enginePositions: Map<String, Position>,
): List<PositionDelta> {
    val ownerBroker = netBySymbol(brokerTickets.filter { attribution.ownerOf(it.ticket) == ownerId })
    val engine =
        enginePositions.entries
            .groupBy { bareSymbol(it.key) }
            .mapValues { (_, es) -> es.fold(BigDecimal.ZERO) { acc, e -> acc + e.value.quantity } }

    val deltas =
        (ownerBroker.keys + engine.keys).toSortedSet().mapNotNull { symbol ->
            val engineQty = engine[symbol] ?: BigDecimal.ZERO
            val brokerQty = ownerBroker[symbol] ?: BigDecimal.ZERO
            if (engineQty.compareTo(brokerQty) == 0) null else PositionDelta(symbol, engineQty, brokerQty)
        }
    val orphans =
        netBySymbol(brokerTickets.filter { attribution.ownerOf(it.ticket) == null }).mapNotNull { (symbol, qty) ->
            if (qty.compareTo(BigDecimal.ZERO) ==
                0
            ) {
                null
            } else {
                PositionDelta("unattributed:$symbol", BigDecimal.ZERO, qty)
            }
        }
    return deltas + orphans
}
