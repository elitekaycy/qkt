package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent

/**
 * A stream's resting orders at a roll: pulled off the old contract without the engine seeing it, then
 * re-placed on the new contract at the same series level under `<engine id>~r<n>`, or cancelled with
 * a reason the engine does see.
 */
internal class RestingOrdersAtRoll(
    private val bus: EventBus,
    private val clock: Clock,
    private val venue: ContractVenue,
    private val orders: ContinuousOrderMap,
    private val legs: RollLegs,
) {
    /** Cancel every order working on contract [index] at the venue, silently; returns them. */
    fun pull(index: Int): List<ContinuousOrder> {
        val resting = orders.on(index)
        for (order in resting) {
            legs.cancelling(order.venueId)
            venue.broker.cancel(order.venueId)
        }
        return resting
    }

    /** Re-place [order] on contract [toIndex] ([to]), its levels mapped through [space]. */
    fun replace(
        order: ContinuousOrder,
        toIndex: Int,
        to: String,
        space: PriceSpace,
    ) {
        val n = order.replacements + 1
        val venueId = "${order.request.id}~r$n"
        orders.add(order.copy(venueId = venueId, contractIndex = toIndex, replacements = n))
        venue.broker.submit(requireNotNull(toContract(order.request, venueId, to, space)))
    }

    /** Tell the engine [order] is cancelled, for [reason]. */
    fun cancel(
        order: ContinuousOrder,
        reason: String,
    ) {
        orders.removeByVenueId(order.venueId)
        bus.publish(BrokerEvent.OrderCancelled(order.request.id, null, reason, order.request.strategyId, clock.now()))
    }
}
