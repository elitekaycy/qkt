package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest

/**
 * Books a venue position as the fill of a placement whose acknowledgement was lost. The ticket is
 * claimed atomically first, so two resolutions racing for the same look-alike position cannot both
 * take it: e.g. tier7 claims ticket 3270423617 and publishes Accepted + Filled at its open price;
 * tier8, arriving a moment later for the same ticket, gets false and looks again.
 */
internal class MT5UnknownFillClaim(
    private val books: MT5BrokerState,
    private val bus: EventBus,
    private val clock: Clock,
) {
    /** Claims [position] for [request] and publishes its fill; false when another order already owns the ticket. */
    fun claimFilled(
        request: OrderRequest,
        position: MT5Position,
        protection: MT5PositionProtection?,
    ): Boolean {
        if (!books.positionBook.claim(position.ticket, MT5TicketMeta(request.id, request.strategyId, protection))) {
            return false
        }
        books.positionBook.setSymbol(position.ticket, request.symbol)
        val ticket = position.ticket.toString()
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = ticket,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = ticket,
                symbol = request.symbol,
                side = request.side,
                price = position.priceOpen,
                quantity = position.volume,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return true
    }
}
