package com.qkt.connector.bybit

import com.qkt.connector.bybit.spot.BybitSpotStateRecovery.ManagedOrderView
import com.qkt.events.BrokerEvent
import java.math.BigDecimal

/**
 * The orders one Bybit broker placed and whose they are. An order's owner outlives its end: Bybit can
 * push an execution after the order's `Filled` or `Cancelled` update, or only the `/v5/execution/list`
 * replay finds it, and a fill with no owner is never booked (#1330). Owners of ended orders are kept for
 * the last [retainEnded] ended orders. An event that must follow fills not yet booked is held until they
 * are: a cancel reporting more executed (`cumExecQty`) than has been booked, so the engine hears the fill,
 * then the end, as on the gateway path (#1306); and an order's last execution heard before an earlier one,
 * so the order completes after its partial fills.
 */
class BybitOrders(
    private val retainEnded: Int = 10_000,
) {
    private class Held(
        val after: BigDecimal,
        val event: BrokerEvent.OrderEvent,
        val quantity: BigDecimal,
    )

    private val live = LinkedHashMap<String, ManagedOrderView>()
    private val ended = bounded<String>()
    private val booked = bounded<BigDecimal>()
    private val held = LinkedHashMap<String, MutableList<Held>>()

    private fun <V> bounded(): LinkedHashMap<String, V> =
        object : LinkedHashMap<String, V>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean = size > retainEnded
        }

    /** Tracks [order] as placed; done before the send, since its updates can beat the REST reply. */
    @Synchronized
    fun register(order: ManagedOrderView) {
        live[order.clientOrderId] = order
    }

    /** Order [clientOrderId] was never placed: nothing of it will come. */
    @Synchronized
    fun forget(clientOrderId: String) {
        live.remove(clientOrderId)
        ended.remove(clientOrderId)
        booked.remove(clientOrderId)
        held.remove(clientOrderId)
    }

    /** Order [clientOrderId] ended at the venue: it is no longer open, but its owner is kept. */
    @Synchronized
    fun end(clientOrderId: String) {
        live.remove(clientOrderId)?.let { ended[clientOrderId] = it.strategyId }
    }

    /** The open orders, by client order id. */
    @Synchronized
    fun open(): Map<String, ManagedOrderView> = live.toMap()

    /** The symbol of open order [clientOrderId]. */
    @Synchronized
    fun symbolOf(clientOrderId: String): String? = live[clientOrderId]?.symbol

    /** The strategy that owns order [clientOrderId], open or ended; null when this broker did not place it. */
    @Synchronized
    fun strategyOf(clientOrderId: String): String? = live[clientOrderId]?.strategyId ?: ended[clientOrderId]

    /** How much of order [clientOrderId] has been booked. */
    @Synchronized
    fun bookedOf(clientOrderId: String): BigDecimal = booked[clientOrderId] ?: BigDecimal.ZERO

    /**
     * Holds [event] until [after] of its order has been booked; true when held. A held cancel ends the
     * order now; a held fill books its [quantity] when released, by [booked] or [release].
     */
    @Synchronized
    fun hold(
        event: BrokerEvent.OrderEvent,
        after: BigDecimal,
        quantity: BigDecimal = BigDecimal.ZERO,
    ): Boolean {
        val id = event.clientOrderId
        if (strategyOf(id) == null || after <= bookedOf(id)) return false
        held.getOrPut(id) { mutableListOf() } += Held(after, event, quantity)
        if (event is BrokerEvent.OrderCancelled) end(id)
        return true
    }

    /** Books [quantity] executed on [clientOrderId]; returns, in order, the held events that no longer wait. */
    @Synchronized
    fun booked(
        clientOrderId: String,
        quantity: BigDecimal,
    ): List<BrokerEvent.OrderEvent> {
        booked[clientOrderId] = bookedOf(clientOrderId) + quantity
        val waiting = held[clientOrderId] ?: return emptyList()
        val out = mutableListOf<BrokerEvent.OrderEvent>()
        while (true) {
            val next = waiting.filter { it.after <= bookedOf(clientOrderId) }.minByOrNull { it.after } ?: break
            waiting.remove(next)
            booked[clientOrderId] = bookedOf(clientOrderId) + next.quantity
            out += next.event
        }
        if (waiting.isEmpty()) held.remove(clientOrderId)
        return out
    }

    /** The orders with events waiting for executions. */
    @Synchronized
    fun awaitingFills(): Set<String> = held.keys.toSet()

    /** Releases the held events of [clientOrderId], in order, whatever was booked. */
    @Synchronized
    fun release(clientOrderId: String): List<BrokerEvent.OrderEvent> =
        held.remove(clientOrderId).orEmpty().sortedBy { it.after }.map {
            booked[clientOrderId] = bookedOf(clientOrderId) + it.quantity
            it.event
        }
}
