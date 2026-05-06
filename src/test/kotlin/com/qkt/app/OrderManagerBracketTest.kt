package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerBracketTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun bracket(): OrderRequest.Bracket {
        val entry =
            OrderRequest.Limit(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        return OrderRequest.Bracket(
            id = "b1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry = entry,
            takeProfit = Money.of("110"),
            stopLoss = Money.of("95"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )
    }

    @Test
    fun `Bracket with native capability ships whole to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.BRACKET,
            )
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Bracket::class.java)
    }

    @Test
    fun `Bracket without native capability decomposes to entry first`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.STOP,
            )
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single().id).isEqualTo("e1")
    }

    @Test
    fun `Bracket fallback - entry fill activates TP and SL legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.STOP,
            )
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        assertThat(broker.submits.size).isGreaterThanOrEqualTo(3)
        assertThat(broker.submits.map { it::class.simpleName })
            .contains("Limit", "Stop")
    }

    @Test
    fun `Bracket fallback - TP fill cancels SL`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps = setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        val tp = broker.submits.first { it is OrderRequest.Limit && it.id != "e1" }
        broker.emitFill(tp, price = Money.of("110"))

        val slId = broker.submits.first { it is OrderRequest.Stop }.id
        assertThat(broker.cancels).contains(slId)
    }
}
