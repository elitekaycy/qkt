package com.qkt.app

import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

object OrderManagerAttachedBracketCloseFixtures {
    fun newBus(clock: FixedClock): EventBus = EventBus(clock, MonotonicSequenceGenerator())

    val attachCaps =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.BRACKET,
            OrderTypeCapability.POSITION_MODIFY,
        )

    fun bracket(stopLoss: StopLossSpec): OrderRequest.Bracket {
        val entry =
            OrderRequest.Stop(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        return OrderRequest.Bracket(
            id = "b1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry = entry,
            takeProfit = Money.of("120"),
            stopLoss = stopLoss,
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )
    }

    fun venueClose(
        bus: EventBus,
        clock: FixedClock,
        quantity: BigDecimal,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "e1",
                brokerOrderId = "tkt-1",
                symbol = "X",
                side = Side.SELL,
                price = Money.of("120"),
                quantity = quantity,
                timestamp = clock.now(),
                updatesOrderExecution = false,
            ),
        )
    }
}
