package com.qkt.app

import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderState
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tells an execution report the order already holds from a new execution, so the position ledger
 * books each venue execution once. E.g. order s-1 filled 50 and the stream replays that fill after a
 * reconnect: the order is already FILLED, so the replay books nothing; a slice reporting cumulative
 * 30 when the order already holds 30 is the same slice heard again. A fill that only reports a venue
 * position close (`updatesOrderExecution = false`) is never a repeat. Engine thread only.
 */
internal class RepeatedExecutions(
    private val orderFor: (String) -> ManagedOrder?,
) {
    /** Slices judged repeated before the order manager applied them, until their booking reads the verdict. */
    private val repeatedSlices: MutableSet<BrokerEvent.OrderPartiallyFilled> =
        Collections.newSetFromMap(IdentityHashMap())

    /** True when [e] repeats a completed order's fill. Read before the order manager books [e]. */
    fun isRepeatedFill(e: BrokerEvent.OrderFilled): Boolean =
        e.updatesOrderExecution && orderFor(e.clientOrderId)?.state == OrderState.FILLED

    /** Judges [e] against the order's executed quantity before the order manager applies it. */
    fun observeSlice(e: BrokerEvent.OrderPartiallyFilled) {
        val order = orderFor(e.clientOrderId) ?: return
        if (order.state == OrderState.FILLED || e.cumulativeFilled <= order.cumulativeFilledQuantity) {
            repeatedSlices.add(e)
        }
    }

    /** True, once, when [observeSlice] judged [e] a repeat. */
    fun takeRepeatedSlice(e: BrokerEvent.OrderPartiallyFilled): Boolean = repeatedSlices.remove(e)
}
