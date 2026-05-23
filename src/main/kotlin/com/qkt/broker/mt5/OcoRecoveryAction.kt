package com.qkt.broker.mt5

import com.qkt.execution.ManagedOrder

/** What restart recovery must do for one OCO leg, given venue truth. */
internal sealed interface OcoRecoveryAction {
    /** Leg is still pending on the venue — re-seed broker tracking. */
    data class Reseed(
        val order: ManagedOrder,
        val ticket: Long,
    ) : OcoRecoveryAction

    /** Leg's ticket is now an open position — it filled while down; republish the fill. */
    data class EmitFill(
        val order: ManagedOrder,
        val position: MT5Position,
    ) : OcoRecoveryAction
}

/**
 * Join recovered [orders] to venue truth by ticket. A leg still in [pendingTickets] is
 * re-seeded; a leg whose ticket is an open [positions] entry filled during downtime; a
 * leg in neither was cancelled/expired and is left for the pending poller to reconcile.
 */
internal fun classifyOcoRecovery(
    orders: List<ManagedOrder>,
    pendingTickets: Set<Long>,
    positions: List<MT5Position>,
): List<OcoRecoveryAction> {
    val positionByTicket = positions.associateBy { it.ticket }
    return orders.mapNotNull { order ->
        val ticket = order.brokerOrderId?.toLongOrNull() ?: return@mapNotNull null
        when {
            ticket in pendingTickets -> OcoRecoveryAction.Reseed(order, ticket)
            positionByTicket.containsKey(ticket) ->
                OcoRecoveryAction.EmitFill(order, positionByTicket.getValue(ticket))
            else -> null
        }
    }
}
