package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.Position

/**
 * Decides which venue position tickets a strategy's startup reconcile may treat as its own.
 * A ticket recorded for, or commented by, another strategy is excluded; one with no qkt marker
 * stays in scope so an unknown position still fails the reconcile closed, e.g. a ticket whose
 * comment is `dsl-hs-12` is left out of strategy `gold`'s reconcile on a shared account.
 */
internal class ReconcileTicketScope(
    private val ticketAttribution: TicketAttribution,
) {
    /** The ticket as the signed position the ticketless broker read would report. */
    fun ticketPosition(ticket: BrokerPositionTicket): Position =
        Position(
            symbol = ticket.symbol,
            quantity =
                if (ticket.side == com.qkt.common.Side.BUY) {
                    ticket.qty
                } else {
                    ticket.qty.negate()
                },
            avgEntryPrice = ticket.entryPrice,
        )

    fun ticketSnapshotMatches(
        brokerPositions: Map<String, List<Position>>,
        tickets: List<BrokerPositionTicket>,
    ): Boolean {
        val ticketPositions =
            tickets
                .groupBy(BrokerPositionTicket::symbol)
                .mapValues { (_, values) -> values.map(::ticketPosition) }
        if (brokerPositions.keys != ticketPositions.keys) return false
        return brokerPositions.all { (symbol, positions) ->
            val unmatched = ticketPositions.getValue(symbol).toMutableList()
            val allMatched =
                positions.all { position ->
                    val index =
                        unmatched.indexOfFirst { candidate ->
                            candidate.quantity.compareTo(position.quantity) == 0 &&
                                candidate.avgEntryPrice.compareTo(position.avgEntryPrice) == 0
                        }
                    if (index < 0) {
                        false
                    } else {
                        unmatched.removeAt(index)
                        true
                    }
                }
            allMatched && unmatched.isEmpty()
        }
    }

    private fun qktOrderMarker(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val marker =
            if (value.startsWith("oco:")) {
                value.substringAfter('/', missingDelimiterValue = "")
            } else {
                value
            }
        return marker.takeIf { it.startsWith("dsl-") }
    }

    fun isPotentiallyOwnedBy(
        ticket: BrokerPositionTicket,
        strategyId: String,
    ): Boolean {
        ticketAttribution.ownerOf(ticket.ticket)?.let { return it == strategyId }
        val marker = qktOrderMarker(ticket.clientOrderId) ?: qktOrderMarker(ticket.comment) ?: return true
        return ticketAttribution.fromComment(marker, listOf(strategyId)) == strategyId
    }
}
