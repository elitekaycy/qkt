package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.events.BrokerEvent

/**
 * A stream's resting orders at a roll: pulled off the old contract without the engine seeing it and,
 * once the venue confirms the cancel, settled: re-placed for what is left of them on the new contract at
 * the same series level under `<engine id>~r<n>`, or cancelled with a reason the engine does see.
 */
internal class RestingOrdersAtRoll(
    private val bus: EventBus,
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val orders: ContinuousOrderMap,
    private val legs: RollLegs,
) {
    /** Cancel [resting] at the venue, silently: every cancel is expected before the first is sent. */
    fun pull(resting: List<ContinuousOrder>) {
        resting.forEach(legs::cancelling)
        resting.forEach { venue.broker.cancel(it.venueId) }
    }

    /**
     * Settles [order], whose cancel the venue confirmed: cancelled for the engine when its strategy was
     * stopped ([stopped]) or the engine cancelled it meanwhile, else re-placed for what is left of it on
     * contract [toIndex].
     */
    fun settle(
        order: ContinuousOrder,
        stopped: String?,
        toIndex: Int,
    ) = when {
        stopped != null -> cancel(order, stopped)
        order.cancelRequested -> cancel(order, "cancelled at the strategy's request while its roll re-placement waited")
        else -> replace(order, toIndex)
    }

    private fun replace(
        order: ContinuousOrder,
        toIndex: Int,
    ) {
        val chain = chainOf()
        val n = order.replacements + 1
        val venueId = "${order.request.id}~r$n"
        orders.add(order.copy(venueId = venueId, contractIndex = toIndex, replacements = n, placed = order.remaining))
        val request =
            toContract(order.request, venueId, chain.contractSymbol(toIndex), chain.spaceFor(toIndex), order.remaining)
        venue.broker.submit(requireNotNull(request))
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
