package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionLeg

/**
 * Builds the market orders that flatten a strategy leg by leg.
 *
 * One rule for every venue: each open leg gets its own opposite-side market order carrying a
 * [LegIntent.Close] that names the leg and, when the leg is pinned to a venue ticket, the
 * ticket. The ledger then realizes exactly that leg when the fill arrives; a hedging venue
 * closes the exact position instead of opening a counter; a netting venue and the backtest
 * reach the same end state by quantity.
 */
internal object LegFlattener {
    fun closeLeg(
        strategyId: String,
        leg: PositionLeg,
        id: String,
        now: Long,
    ): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = leg.symbol,
            side = if (leg.side == Side.BUY) Side.SELL else Side.BUY,
            quantity = leg.quantity,
            timeInForce = TimeInForce.GTC,
            timestamp = now,
            strategyId = strategyId,
            closesTicket = leg.brokerTicket,
            closesLegId = leg.legId,
            legIntent = LegIntent.Close(legId = leg.legId, ticket = leg.brokerTicket),
        )

    /** A venue position attributed to the strategy that the ledger does not hold: close it by ticket. */
    fun closeTicket(
        strategyId: String,
        ticket: BrokerPositionTicket,
        id: String,
        now: Long,
    ): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = ticket.symbol,
            side = if (ticket.side == Side.BUY) Side.SELL else Side.BUY,
            quantity = ticket.qty,
            timeInForce = TimeInForce.GTC,
            timestamp = now,
            strategyId = strategyId,
            closesTicket = ticket.ticket,
            legIntent = LegIntent.Close(ticket = ticket.ticket),
        )
}
