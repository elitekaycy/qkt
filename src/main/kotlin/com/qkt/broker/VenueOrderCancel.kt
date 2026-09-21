package com.qkt.broker

/**
 * A broker that can cancel a resting order by its venue ticket, whether or not the session ever
 * learned that ticket - the one way to remove an order whose placement response was lost.
 */
interface VenueOrderCancel {
    /** True when the venue accepted the cancel; false when it refused or the ticket is not this broker's. */
    fun cancelVenueOrder(ticket: String): Boolean
}

/**
 * [VenueOrderCancel] for a router over several brokers: the cancel goes to the leaf whose venue is
 * resting that ticket. Two venues can issue the same number, so the ticket alone does not pick one.
 */
internal class RoutedVenueOrderCancel(
    private val leaves: List<Broker>,
) : VenueOrderCancel {
    override fun cancelVenueOrder(ticket: String): Boolean =
        leaves.distinct().any { leaf ->
            leaf is VenueOrderCancel &&
                runCatching { leaf.pendingOrders().any { it.ticket == ticket } }.getOrDefault(false) &&
                leaf.cancelVenueOrder(ticket)
        }
}
