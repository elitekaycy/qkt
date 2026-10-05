package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Publishes one [category] broker's fills and order ends in the order the engine must hear them (see
 * [BybitOrders.hold]): a fill as a partial or a completing fill ([BybitFills]), an order's last execution
 * only after the ones before it, and a cancel only after the executions it reports. On each reconcile
 * ([resolve]) an order with held events has its executions read back by id; events still held at the
 * next reconcile are released, logged, without them.
 */
class BybitHeldEnds(
    val orders: BybitOrders,
    private val bus: EventBus,
    private val clock: Clock,
    private val category: String,
) {
    private val log = LoggerFactory.getLogger(BybitHeldEnds::class.java)
    private var awaitingLastTime = emptySet<String>()

    /** Publishes the fill [exec] of [strategyId]'s order, or holds it while an earlier execution is unheard. */
    fun fill(
        exec: BybitOrderTranslator.ParsedExecution,
        strategyId: String,
    ) {
        val id = exec.clientOrderId
        val event = BybitFills.event(category, exec, strategyId, clock.now(), orders.bookedOf(id))
        if (event is BrokerEvent.OrderFilled && orders.hold(event, BybitFills.executedBefore(exec), exec.quantity)) {
            return
        }
        bus.publish(event)
        orders.booked(id, exec.quantity).forEach(bus::publish)
    }

    /** Publishes [end], or holds it while less than [executed] of its order has been booked. */
    fun end(
        end: BrokerEvent.OrderCancelled,
        executed: BigDecimal,
    ) {
        if (!orders.hold(end, executed)) bus.publish(end)
    }

    /** Replays each order with held events through [replay]; releases what was held since the last call. */
    @Synchronized
    fun resolve(replay: BybitExecutionReplay) {
        val awaiting = orders.awaitingFills()
        awaiting.forEach(replay::replayOrder)
        for (id in awaiting intersect awaitingLastTime) {
            val released = orders.release(id)
            if (released.isEmpty()) continue
            log.warn("Bybit order {} has executions never seen; releasing what waited on them", id)
            released.forEach(bus::publish)
        }
        awaitingLastTime = orders.awaitingFills()
    }
}
