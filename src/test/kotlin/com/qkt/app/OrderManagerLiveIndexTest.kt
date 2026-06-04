package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerLiveIndexTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `a pending order still fires after many finished orders precede it`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        // MARKET capability only so Stop falls through to holdPending.
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        repeat(50) { i ->
            om.submit(
                OrderRequest.Market(
                    id = "done-$i",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = "done-$i",
                    brokerOrderId = "done-$i",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    price = Money.of("1.10"),
                    quantity = Money.of("1"),
                ),
            )
        }

        om.submit(
            OrderRequest.Stop(
                id = "rest",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("rest")!!.state).isEqualTo(OrderState.PENDING)

        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.25"), 1L)))

        assertThat(om.getOrder("rest")!!.state).isEqualTo(OrderState.WORKING)
    }
}
