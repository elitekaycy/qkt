package com.qkt.app

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
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

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
    }

    @Test
    fun `submit Market goes to broker and tracks state through accept`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val tracker = MarketPriceTracker()
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, tracker, clock)

        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val ack = om.submit(req)

        assertThat(ack.accepted).isTrue()
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.WORKING)
        assertThat(managed.brokerOrderId).isEqualTo("c1")
    }

    private fun bracket(
        id: String,
        entryId: String,
    ) = OrderRequest.Bracket(
        id = id,
        symbol = "EURUSD",
        side = Side.BUY,
        quantity = Money.of("1"),
        entry =
            OrderRequest.Market(
                id = entryId,
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        takeProfit = Money.of("1.12"),
        stopLoss = StopLossSpec.Fixed(Money.of("1.09")),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `risk is not recorded when tracking is off, so the live map does not leak`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("EURUSD", Money.of("1.10")) }
        val om = OrderManager(broker, bus, tracker, clock, trackRisk = false)

        om.submit(bracket("b1", "e1"))

        assertThat(om.riskUsdFor("e1")).isNull()
        assertThat(om.riskUsdFor("b1")).isNull()
    }

    @Test
    fun `risk is recorded for the backtest report when tracking is on`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("EURUSD", Money.of("1.10")) }
        val om = OrderManager(broker, bus, tracker, clock, trackRisk = true)

        om.submit(bracket("b1", "e1"))

        // risk = |entry 1.10 - stop 1.09| * qty 1 = 0.01
        assertThat(om.riskUsdFor("e1")).isEqualByComparingTo("0.01")
    }

    @Test
    fun `submit Limit goes to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c2")?.state).isEqualTo(OrderState.WORKING)
    }

    @Test
    fun `OrderFilled event transitions state to FILLED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("1"))
        assertThat(managed.avgFillPrice).isEqualByComparingTo(Money.of("1.10"))
    }

    @Test
    fun `OrderRejected event transitions state to REJECTED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                reason = "no price",
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.REJECTED)
    }

    @Test
    fun `OrderPartiallyFilled accumulates cumulative fill quantity`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("3"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("2"),
                cumulativeFilled = Money.of("3"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("3"))
    }

    @Test
    fun `activeOrders excludes terminal states`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.submit(
            OrderRequest.Market(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )

        assertThat(om.activeOrders().map { it.id }).containsExactly("c2")
    }

    @Test
    fun `cancel routes to broker for working order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.WORKING)

        om.cancel("c1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `cancel of unknown order is a no-op`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.cancel("does-not-exist")
        assertThat(om.getOrder("does-not-exist")).isNull()
    }

    @Test
    fun `orderDetailsFor returns symbol side and quantity for a submitted order`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("0.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )

        val details = om.orderDetailsFor("c1")
        assertThat(details).isNotNull
        assertThat(details!!.symbol).isEqualTo("EURUSD")
        assertThat(details.side).isEqualTo(Side.SELL)
        assertThat(details.quantity).isEqualByComparingTo(Money.of("0.5"))
    }

    @Test
    fun `orderDetailsFor still resolves after the order is rejected`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )
        bus.publish(BrokerEvent.OrderRejected(clientOrderId = "c1", brokerOrderId = null, reason = "test"))

        val details = om.orderDetailsFor("c1")
        assertThat(details).isNotNull
        assertThat(details!!.symbol).isEqualTo("XAUUSD")
        assertThat(details.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `orderDetailsFor returns null for an unknown order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        assertThat(om.orderDetailsFor("never-submitted")).isNull()
    }
}
