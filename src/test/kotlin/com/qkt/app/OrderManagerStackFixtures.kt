package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

object OrderManagerStackFixtures {
    fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    fun publishLayerFill(
        bus: EventBus,
        stack: OrderRequest.Stack,
        layer: Int,
        ticket: String,
        quantity: BigDecimal,
        clock: FixedClock,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${stack.id}-l$layer",
                brokerOrderId = ticket,
                symbol = stack.symbol,
                side = stack.side,
                price = BigDecimal("50000") + BigDecimal(layer - 1).multiply(BigDecimal("100")),
                quantity = quantity,
                timestamp = clock.now(),
            ),
        )
    }
}
