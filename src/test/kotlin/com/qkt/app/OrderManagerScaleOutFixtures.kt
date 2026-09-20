package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

object OrderManagerScaleOutFixtures {
    fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    fun marketBasis(quantity: String): OrderRequest.Market =
        OrderRequest.Market(
            id = "e1",
            symbol = "X",
            side = Side.BUY,
            quantity = BigDecimal(quantity),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    fun scaleOut(
        basis: OrderRequest,
        strategyId: String,
    ): OrderRequest.ScaleOut =
        OrderRequest.ScaleOut(
            id = "s1",
            symbol = basis.symbol,
            side = basis.side,
            quantity = basis.quantity,
            basis = basis,
            legs =
                listOf(
                    ScaleOutLeg(Money.of("110"), Money.of("0.5")),
                    ScaleOutLeg(Money.of("120"), Money.of("0.5")),
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )
}
