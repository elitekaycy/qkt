package com.qkt.app

import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.LogBroker
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTerminalStateTest {
    @Test
    fun `a late broker event cannot resurrect a terminal order`() {
        // A FILLED order is a sink: a stale OrderAccepted replayed after the fill must
        // not flip it back to a live state (which would re-arm triggers downstream).
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val clock = FixedClock(time = 0L)
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val broker = PaperBroker(bus, clock, tracker)
        val om = OrderManager(broker, bus, tracker, clock)

        om.submit(
            OrderRequest.Market(
                id = "m-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("m-1")?.state).isEqualTo(OrderState.FILLED)

        // Late/duplicate accept event for the same order.
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = "m-1",
                brokerOrderId = "b-1",
                timestamp = 5L,
            ),
        )
        assertThat(om.getOrder("m-1")?.state).isEqualTo(OrderState.FILLED)

        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = "m-1",
                brokerOrderId = "b-1",
                reason = "late cancel acknowledgement",
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "m-1",
                brokerOrderId = "b-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.20"),
                quantity = Money.of("1"),
            ),
        )
        val terminal = om.getOrder("m-1")!!
        assertThat(terminal.state).isEqualTo(OrderState.FILLED)
        assertThat(terminal.cumulativeFilledQuantity).isEqualByComparingTo("1")
        assertThat(terminal.avgFillPrice).isEqualByComparingTo("1.10")
    }

    @Test
    fun `cancelled order cannot flip to filled on a late event`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        om.submit(
            OrderRequest.Limit(
                id = "c-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("c-1")

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c-1",
                brokerOrderId = "c-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )

        val terminal = om.getOrder("c-1")!!
        assertThat(terminal.state).isEqualTo(OrderState.CANCELLED)
        assertThat(terminal.cumulativeFilledQuantity).isEqualByComparingTo("0")
    }
}
