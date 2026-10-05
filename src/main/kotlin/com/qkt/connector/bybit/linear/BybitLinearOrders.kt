package com.qkt.connector.bybit.linear

import com.qkt.connector.bybit.spot.BybitSpotStateRecovery.ManagedOrderView
import com.qkt.events.BrokerEvent
import java.math.BigDecimal

/**
 * The orders [BybitLinearBroker] placed and whose they are. An order's owner outlives its end: Bybit can
 * push an execution after the order's `Filled` or `Cancelled` update, or only the `/v5/execution/list`
 * replay finds it, and a fill with no owner is never booked (#1330). Owners of ended orders are kept for
 * the last [retainEnded] ended orders. A `Cancelled` update reporting more executed (`cumExecQty`) than
 * has been booked is held until those executions are booked, so the engine hears the fill, then the end,
 * as on the gateway path (#1306).
 */
class BybitLinearOrders(
    private val retainEnded: Int = 10_000,
) {
    private class Held(
        val executed: BigDecimal,
        val end: BrokerEvent.OrderCancelled,
    )

    private val live = LinkedHashMap<String, ManagedOrderView>()
    private val ended = bounded<String>()
    private val booked = bounded<BigDecimal>()
    private val held = LinkedHashMap<String, Held>()

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

    /** The strategy that owns order [clientOrderId], open or ended; null when qkt does not know it. */
    @Synchronized
    fun strategyOf(clientOrderId: String): String? =
        (live[clientOrderId]?.strategyId ?: ended[clientOrderId])?.takeIf { it.isNotBlank() }

    /**
     * Holds [end] while less than [executed] of its order has been booked; true when held, and the order
     * is then ended. A held end is released by [booked] or [release].
     */
    @Synchronized
    fun hold(
        end: BrokerEvent.OrderCancelled,
        executed: BigDecimal,
    ): Boolean {
        val id = end.clientOrderId
        if (strategyOf(id) == null || executed <= (booked[id] ?: BigDecimal.ZERO)) return false
        held[id] = Held(executed, end)
        end(id)
        return true
    }

    /** Books [quantity] executed on [clientOrderId]; returns its held end once all of it is booked. */
    @Synchronized
    fun booked(
        clientOrderId: String,
        quantity: BigDecimal,
    ): BrokerEvent.OrderCancelled? {
        val total = (booked[clientOrderId] ?: BigDecimal.ZERO) + quantity
        booked[clientOrderId] = total
        val pending = held[clientOrderId] ?: return null
        if (total < pending.executed) return null
        return held.remove(clientOrderId)?.end
    }

    /** The orders whose end waits for executions. */
    @Synchronized
    fun awaitingFills(): Set<String> = held.keys.toSet()

    /** Releases the held end of [clientOrderId] whatever was booked. */
    @Synchronized
    fun release(clientOrderId: String): BrokerEvent.OrderCancelled? = held.remove(clientOrderId)?.end
}
