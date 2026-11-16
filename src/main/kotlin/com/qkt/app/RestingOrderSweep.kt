package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.VenueOrderCancel
import com.qkt.observe.insights.TicketAttribution

/**
 * Cancels, by venue ticket, the resting orders whose venue comment names a deployed strategy, including
 * one the engine never learned about because its placement response was lost (#1234).
 */
internal class RestingOrderSweep(
    private val broker: Broker,
    private val attribution: TicketAttribution,
    private val deployedIds: List<String>,
) {
    /** Cancels the first deployed strategy's resting orders; returns the tickets that were at the venue. */
    fun sweep(): List<String> = deployedIds.firstOrNull()?.let(::cancelRestingOrders).orEmpty()

    /** Cancels [strategyId]'s resting orders by venue ticket; returns the tickets still at the venue before this pass. */
    fun cancelRestingOrders(strategyId: String): List<String> {
        val owned =
            runCatching { broker.pendingOrders() }
                .getOrDefault(emptyList())
                .filter { attribution.fromComment(it.clientOrderId ?: it.comment, deployedIds) == strategyId }
        val venue = broker as? VenueOrderCancel
        owned.forEach { order -> runCatching { venue?.cancelVenueOrder(order.ticket) } }
        return owned.map { it.ticket }
    }
}
