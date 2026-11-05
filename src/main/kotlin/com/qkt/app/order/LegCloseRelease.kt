package com.qkt.app.order

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent

/**
 * Ends the hold [LegCloseGuard] keeps on a close when the close covering it ends: a fill, a cancel
 * or a rejection. Costs one empty-map check per order event while nothing is held.
 */
internal class LegCloseRelease(
    private val guard: LegCloseGuard,
    private val ops: OrderOps,
) {
    /** Subscribes to the order events that end a working close. */
    fun subscribe(bus: EventBus) {
        bus.subscribe<BrokerEvent.OrderFilled> { e -> release(e.clientOrderId, filled = true) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> release(e.clientOrderId, filled = false) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> release(e.clientOrderId, filled = false) }
    }

    private fun release(
        id: String,
        filled: Boolean,
    ) {
        if (guard.isEmpty()) return
        guard.release(id, filled) { ops.submit(it) }
    }
}
