package com.qkt.app.order

import com.qkt.broker.withLevels
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent

/**
 * Keeps the engine's view of a working order at the levels its broker last reported ([BrokerEvent.OrderModified]):
 * a venue's accepted modification, or a continuous lane re-expressing the order in a rebuilt roll series (qkt#1754),
 * so the stop and target the engine reports are the ones that map to where the order rests. Quantity is not applied.
 */
internal class WorkingOrderLevels(
    private val ops: OrderOps,
    private val clock: Clock,
) {
    fun subscribe(bus: EventBus) {
        bus.subscribe<BrokerEvent.OrderModified> { onModified(it) }
    }

    /** Applies [e]'s levels to the engine's order; an order the engine does not hold is left alone. */
    fun onModified(e: BrokerEvent.OrderModified) {
        ops.update(e.clientOrderId) { it.copy(request = it.request.withLevels(e.changes), lastUpdatedAt = clock.now()) }
    }
}
