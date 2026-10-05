package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Publishes the order ends [orders] holds for their executions (see [BybitOrders.hold]): an end is
 * released once its fills are booked ([booked]), and on each reconcile ([resolve]) a held order has its
 * executions read back by id through [replay]; an end still held at the next reconcile is released,
 * logged, without them.
 */
class BybitHeldEnds(
    val orders: BybitOrders,
    private val bus: EventBus,
) {
    private val log = LoggerFactory.getLogger(BybitHeldEnds::class.java)
    private var awaitingLastTime = emptySet<String>()

    /** A fill of [execution] was published: books it, then publishes its order's end if that waited on it. */
    fun booked(execution: BybitOrderTranslator.ParsedExecution) {
        orders.booked(execution.clientOrderId, execution.quantity)?.let(bus::publish)
    }

    /** Publishes [end], or holds it while less than [executed] of its order has been booked. */
    fun end(
        end: BrokerEvent.OrderCancelled,
        executed: BigDecimal,
    ) {
        if (!orders.hold(end, executed)) bus.publish(end)
    }

    /** Replays each held order's executions through [replay]; releases ends held since the last call. */
    @Synchronized
    fun resolve(replay: BybitExecutionReplay) {
        val awaiting = orders.awaitingFills()
        awaiting.forEach(replay::replayOrder)
        for (id in awaiting intersect awaitingLastTime) {
            val end = orders.release(id) ?: continue
            log.warn("Bybit order {} ended with executions never seen; releasing its end", id)
            bus.publish(end)
        }
        awaitingLastTime = orders.awaitingFills()
    }
}
