package com.qkt.broker.continuous

import com.qkt.broker.BookedLeg
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest

/** A venue that only records what it is sent; the test publishes its answers. */
internal class ScriptedVenue : Broker {
    override val name = "scripted"
    override val capabilities = setOf(OrderTypeCapability.MARKET)
    val sent = mutableListOf<OrderRequest>()
    val cancels = mutableListOf<String>()
    val recovered = mutableListOf<ManagedOrder>()

    /** The order ids this venue knows when orders are recovered; null when it knows every one. */
    var knows: Set<String>? = null

    /** Called while orders are recovered, before the venue answers: for answers the venue gives on the way. */
    var onRecover: () -> Unit = {}

    /** Whether the session told this venue it is restored. */
    var ready = false

    /** Called with each request as it reaches the venue, before it is recorded. */
    var onSubmit: (OrderRequest) -> Unit = {}

    override fun submit(request: OrderRequest): SubmitAck {
        onSubmit(request)
        sent += request
        return SubmitAck(request.id, null, accepted = true)
    }

    /** Called with each order id whose cancel reaches the venue, before it is recorded. */
    var onCancel: (String) -> Unit = {}

    override fun cancel(orderId: String) {
        onCancel(orderId)
        cancels += orderId
    }

    override fun recoverPendingOrders(
        orders: List<ManagedOrder>,
        bookedTickets: Set<String>,
    ): Set<String> {
        recovered += orders
        val known = orders.map { it.id }.filterTo(LinkedHashSet()) { knows?.contains(it) ?: true }
        onRecover()
        return known
    }

    override fun watchBookedLegs(supplier: () -> List<BookedLeg>) {
        ready = true
    }
}
