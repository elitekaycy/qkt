package com.qkt.app

import com.qkt.broker.FakeBroker
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
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A venue-attached bracket (BRACKET + POSITION_MODIFY) has no resting exit orders: the venue
 * closes the ticket when SL/TP is hit. The bracket wrapper must complete when that close is
 * observed — otherwise every filled bracket stays WORKING for the life of the process, is
 * re-persisted on each state change, and is never reclaimed.
 */
class OrderManagerAttachedBracketCloseTest {
    private fun newBus(clock: FixedClock): EventBus = EventBus(clock, MonotonicSequenceGenerator())

    private val attachCaps =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.BRACKET,
            OrderTypeCapability.POSITION_MODIFY,
        )

    private fun bracket(stopLoss: StopLossSpec): OrderRequest.Bracket {
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

    private fun venueClose(
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

    @Test
    fun `fixed bracket wrapper completes when the venue closes the position`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket(StopLossSpec.Fixed(Money.of("90"))))
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        assertThat(om.activeOrders().map { it.id }).containsExactly("b1")

        venueClose(bus, clock, Money.of("1"))

        assertThat(om.activeOrders()).isEmpty()
    }

    @Test
    fun `partial venue close keeps the wrapper active until the position is flat`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket(StopLossSpec.Fixed(Money.of("90"))))
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        venueClose(bus, clock, Money.of("0.4"))
        assertThat(om.activeOrders().map { it.id }).containsExactly("b1")

        venueClose(bus, clock, Money.of("0.6"))
        assertThat(om.activeOrders()).isEmpty()
    }

    @Test
    fun `armed trail wrapper and its held engine stop complete on venue close`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            bracket(StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10"))),
        )
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        assertThat(om.activeOrders().map { it.id }).containsExactlyInAnyOrder("b1", "b1-sl")

        venueClose(bus, clock, Money.of("1"))

        assertThat(om.activeOrders()).isEmpty()
        assertThat(broker.submits).hasSize(1)
    }
}
