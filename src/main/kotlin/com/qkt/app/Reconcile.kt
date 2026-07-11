package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.PositionLeg
import java.math.BigDecimal

/** Bare symbol without the broker prefix: "EXNESS:XAUUSD" -> "XAUUSD"; an already-bare key is returned unchanged. */
private fun bareSymbol(symbol: String): String = symbol.substringAfter(":")

private data class DirectionKey(
    val symbol: String,
    val side: Side,
)

private fun brokerByDirection(tickets: List<BrokerPositionTicket>): Map<DirectionKey, BigDecimal> =
    tickets.groupingBy { DirectionKey(bareSymbol(it.symbol), it.side) }.fold(BigDecimal.ZERO) { acc, ticket ->
        acc + ticket.qty
    }

private fun engineByDirection(legs: List<PositionLeg>): Map<DirectionKey, BigDecimal> =
    legs.groupingBy { DirectionKey(bareSymbol(it.symbol), it.side) }.fold(BigDecimal.ZERO) { acc, leg ->
        acc + leg.quantity
    }

private fun netFor(
    quantities: Map<DirectionKey, BigDecimal>,
    symbol: String,
): BigDecimal =
    (quantities[DirectionKey(symbol, Side.BUY)] ?: BigDecimal.ZERO) -
        (quantities[DirectionKey(symbol, Side.SELL)] ?: BigDecimal.ZERO)

/**
 * Engine-vs-broker position deltas for one strategy on a (possibly shared) account.
 *
 * Confirmed netting symbols compare signed net quantity. Hedging and unknown symbols compare
 * positive gross quantity per direction, so equal-and-opposite exposure cannot disappear into
 * zero. Broker tickets are scoped by [attribution]; unattributed tickets always surface per
 * direction, including net-zero long/short orphan pairs.
 */
fun reconcileDeltas(
    ownerId: String,
    brokerTickets: List<BrokerPositionTicket>,
    attribution: TicketAttribution,
    engineLegs: List<PositionLeg>,
    accountingModes: Map<String, PositionAccountingMode> = emptyMap(),
): List<PositionDelta> {
    val ownerBroker = brokerByDirection(brokerTickets.filter { attribution.ownerOf(it.ticket) == ownerId })
    val engine = engineByDirection(engineLegs)
    val symbols = (ownerBroker.keys.map { it.symbol } + engine.keys.map { it.symbol }).toSortedSet()
    val deltas = mutableListOf<PositionDelta>()
    for (symbol in symbols) {
        if (accountingModes[symbol] == PositionAccountingMode.NETTING) {
            val engineQty = netFor(engine, symbol)
            val brokerQty = netFor(ownerBroker, symbol)
            if (engineQty.compareTo(brokerQty) != 0) {
                deltas.add(PositionDelta(symbol, engineQty, brokerQty))
            }
            continue
        }
        for (side in Side.entries) {
            val key = DirectionKey(symbol, side)
            val engineQty = engine[key] ?: BigDecimal.ZERO
            val brokerQty = ownerBroker[key] ?: BigDecimal.ZERO
            if (engineQty.compareTo(brokerQty) != 0) {
                deltas.add(PositionDelta(symbol, engineQty, brokerQty, side))
            }
        }
    }
    val orphans =
        brokerByDirection(brokerTickets.filter { attribution.ownerOf(it.ticket) == null }).map { (key, qty) ->
            PositionDelta("unattributed:${key.symbol}", BigDecimal.ZERO, qty, key.side)
        }
    return deltas + orphans.sortedWith(compareBy<PositionDelta> { it.symbol }.thenBy { it.side })
}

/** Protection drift for tickets owned by [ownerId] and carrying qkt-requested levels. */
fun reconcileProtectionDeltas(
    ownerId: String,
    brokerTickets: List<BrokerPositionTicket>,
    attribution: TicketAttribution,
): List<PositionProtectionDelta> =
    brokerTickets
        .filter { attribution.ownerOf(it.ticket) == ownerId }
        .mapNotNull { ticket ->
            val stopDiffers =
                ticket.requestedStopLoss != null &&
                    ticket.stopLoss?.compareTo(ticket.requestedStopLoss) != 0
            val takeProfitDiffers =
                ticket.requestedTakeProfit != null &&
                    ticket.takeProfit?.compareTo(ticket.requestedTakeProfit) != 0
            if (!stopDiffers && !takeProfitDiffers) {
                null
            } else {
                PositionProtectionDelta(
                    ticket = ticket.ticket,
                    symbol = ticket.symbol,
                    requestedStopLoss = ticket.requestedStopLoss,
                    brokerStopLoss = ticket.stopLoss,
                    requestedTakeProfit = ticket.requestedTakeProfit,
                    brokerTakeProfit = ticket.takeProfit,
                )
            }
        }.sortedBy { it.ticket }
