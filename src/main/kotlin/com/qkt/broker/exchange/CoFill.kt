package com.qkt.broker.exchange

import com.qkt.common.Side
import com.qkt.execution.OrderRequest

/**
 * Which resting orders can fill on one tick together: those on the same side set off the same way. A
 * market order fills at once; a buy limit and a sell stop fill as the price falls to them; a sell limit and
 * a buy stop as it rises. So a stop and a take-profit protecting one position never fill on the same tick.
 */
internal object CoFill {
    private enum class Trigger { NOW, FALLING, RISING }

    /** Whether [a] and [b] could both fill on one tick. */
    fun together(
        a: OrderRequest,
        b: OrderRequest,
    ): Boolean = a.side == b.side && trigger(a) == trigger(b)

    private fun trigger(order: OrderRequest): Trigger =
        when (order) {
            is OrderRequest.Market -> Trigger.NOW
            is OrderRequest.Limit -> if (order.side == Side.BUY) Trigger.FALLING else Trigger.RISING
            is OrderRequest.Stop, is OrderRequest.StopLimit ->
                if (order.side ==
                    Side.BUY
                ) {
                    Trigger.RISING
                } else {
                    Trigger.FALLING
                }
            else -> Trigger.NOW
        }
}
