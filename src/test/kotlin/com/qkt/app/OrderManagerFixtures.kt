package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce

object OrderManagerFixtures {
    fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    fun bracket(
        id: String,
        entryId: String,
        symbol: String = "EURUSD",
        entry: String = "1.10",
        stop: String = "1.09",
    ) = OrderRequest.Bracket(
        id = id,
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        entry =
            OrderRequest.Market(
                id = entryId,
                symbol = symbol,
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        takeProfit = Money.of(entry).add(Money.of("20")),
        stopLoss = StopLossSpec.Fixed(Money.of(stop)),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )
}
