package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick

/** Cancels a simulated broker's working orders whose GTD/DAY deadline a tick of their symbol has reached. */
internal class PaperDeadlines(
    private val bus: EventBus,
    private val clock: Clock,
    private val reason: (OrderRequest) -> String = { "${it.timeInForce} expired" },
) {
    // Reused per-tick scratch; the broker is single-threaded.
    private val expired: MutableList<OrderRequest> = mutableListOf()

    /** Removes each order in [working] on [tick]'s symbol whose deadline it reached, publishing its cancel. */
    fun expire(
        working: MutableList<OrderRequest>,
        tick: Tick,
        onExpired: (OrderRequest) -> Unit,
    ) {
        expired.clear()
        for (i in working.indices) {
            val request = working[i]
            if (request.symbol != tick.symbol) continue
            val expiresAt = request.expiresAt ?: continue
            if (tick.timestamp >= expiresAt) expired.add(request)
        }
        for (i in expired.indices) {
            val request = expired[i]
            if (!working.remove(request)) continue
            onExpired(request)
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = request.id,
                    brokerOrderId = request.id,
                    reason = reason(request),
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
    }
}
