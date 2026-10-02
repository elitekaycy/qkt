package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest

/**
 * The client order id the gateway sees for an engine order: the engine's id and its submit time in
 * base 36 (`dsl-s--4` submitted at 1790835377133 is `dsl-s--4.mg8q2kv1`). A gateway remembers every id
 * for ever (idempotent submits, ids written off as dead), while an engine may hand an id out again
 * after a restart once its order has ended; the submit time makes every order's id new, and a restored
 * order's id is rebuilt from its persisted request. The engine's id is everything before the last `.`.
 */
internal object GatewayClientIds {
    /** The longest client order id VGP v1 carries (the venue label it travels in). */
    const val MAX_LENGTH = 64

    /** [request]'s client order id at the gateway. */
    fun of(request: OrderRequest): String = "${request.id}.${request.timestamp.toString(36)}"

    /** The engine order id behind gateway id [clientOrderId]. */
    fun engineId(clientOrderId: String): String = clientOrderId.substringBeforeLast('.')

    /** [event] carrying the engine's order id instead of the gateway's. */
    fun toEngine(event: BrokerEvent.OrderEvent): BrokerEvent.OrderEvent {
        val id = engineId(event.clientOrderId)
        return when (event) {
            is BrokerEvent.OrderAccepted -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderCancelled -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderRejected -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderPartiallyFilled -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderFilled -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderCancelFailed -> event.copy(clientOrderId = id)
            is BrokerEvent.OrderModified -> event.copy(clientOrderId = id)
        }
    }
}
