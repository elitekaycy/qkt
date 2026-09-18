package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce

object OrderManagerOcoFixtures {
    fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(
        id = id,
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )
}
