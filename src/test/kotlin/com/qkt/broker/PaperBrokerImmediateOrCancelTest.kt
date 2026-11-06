package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperBrokerImmediateOrCancelTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val cancels = mutableListOf<BrokerEvent.OrderCancelled>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels += it }
    }

    private fun limit(
        price: String,
        tif: TimeInForce,
    ) = OrderRequest.Limit("l", "X", Side.BUY, Money.of("1"), Money.of(price), tif, 0L, "s1")

    @Test
    fun `an immediate-or-cancel order with no price to meet is cancelled at once`() {
        PaperBroker(bus, clock, tracker).submit(limit("100", TimeInForce.IOC))

        assertThat(fills).isEmpty()
        assertThat(cancels.single().reason).isEqualTo("IOC unfilled")
        assertThat(cancels.single().strategyId).isEqualTo("s1")
    }

    @Test
    fun `a fill-or-kill limit away from the price is cancelled at once`() {
        tracker.update("X", Money.of("105"))

        PaperBroker(bus, clock, tracker).submit(limit("100", TimeInForce.FOK))

        assertThat(fills).isEmpty()
        assertThat(cancels.single().reason).isEqualTo("FOK unfilled")
    }

    @Test
    fun `an immediate-or-cancel stop limit whose limit is not marketable on activation is cancelled, not rested`() {
        tracker.update("X", Money.of("100"))
        val broker = PaperBroker(bus, clock, tracker)

        broker.submit(
            OrderRequest.StopLimit(
                "sl",
                "X",
                Side.BUY,
                Money.of("1"),
                Money.of("99"),
                Money.of("98"),
                TimeInForce.IOC,
                0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("97"), 1L)))

        assertThat(cancels.single().clientOrderId).isEqualTo("sl")
        assertThat(fills).isEmpty()
    }

    @Test
    fun `on bars an if-touched market order the gap traded through fills at its trigger`() {
        val broker = PaperBroker(bus, clock, tracker, fillAtTriggerPrice = { true })
        bus.publish(TickEvent(Tick("X", Money.of("105"), 1L)))
        broker.submit(
            OrderRequest.IfTouched(
                id = "it",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("100"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(TickEvent(Tick("X", Money.of("95"), 2L)))

        assertThat(fills.single().price).isEqualByComparingTo("100")
    }
}
