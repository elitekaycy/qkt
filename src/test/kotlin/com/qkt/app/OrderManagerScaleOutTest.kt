package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerScaleOutTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `ScaleOut submits basis only`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                basis = basis,
                legs =
                    listOf(
                        ScaleOutLeg(Money.of("110"), Money.of("0.33")),
                        ScaleOutLeg(Money.of("120"), Money.of("0.33")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("e1")
    }

    @Test
    fun `basis fill activates leg orders sized by fraction`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(
                    OrderTypeCapability.MARKET,
                    OrderTypeCapability.LIMIT,
                    OrderTypeCapability.IF_TOUCHED,
                ),
            )
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                basis = basis,
                legs =
                    listOf(
                        ScaleOutLeg(Money.of("110"), Money.of("0.5")),
                        ScaleOutLeg(Money.of("120"), Money.of("0.5")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(basis, price = Money.of("100"))

        val legSubmits = broker.submits.filter { it is OrderRequest.IfTouched }
        assertThat(legSubmits).hasSize(2)
        assertThat(legSubmits.first().quantity).isEqualByComparingTo(Money.of("1.5"))
    }

    @Test
    fun `ScaleOut leg side is opposite of basis side`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.IF_TOUCHED))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(basis, price = Money.of("100"))

        val leg = broker.submits.first { it is OrderRequest.IfTouched } as OrderRequest.IfTouched
        assertThat(leg.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `cancelling ScaleOut before basis fill cancels basis`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("s1")

        assertThat(broker.cancels).contains("e1")
    }
}
