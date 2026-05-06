package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOtoTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `OTO submits parent only initially`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val child =
            OrderRequest.Limit(
                id = "c1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CREATED)
    }

    @Test
    fun `parent fill activates children`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val child =
            OrderRequest.Limit(
                id = "c1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(parent, price = Money.of("100"))

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("p1", "c1")
    }

    @Test
    fun `cancel before parent fill cancels parent and children stay CREATED then CANCELLED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val child =
            OrderRequest.Limit(
                id = "c1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("oto1")

        assertThat(broker.cancels).contains("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }
}
