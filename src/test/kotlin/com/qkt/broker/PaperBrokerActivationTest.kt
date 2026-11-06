package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaperBrokerActivationTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
    }

    private fun tick(
        price: String,
        ts: Long,
    ) = bus.publish(TickEvent(Tick("X", Money.of(price), ts)))

    private fun ifTouchedLimit(
        side: Side,
        trigger: String,
        limit: String,
    ) = OrderRequest.IfTouched(
        id = "it",
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        triggerPrice = Money.of(trigger),
        onTrigger = TriggerType.LIMIT,
        limitPrice = Money.of(limit),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        strategyId = "s1",
    )

    @Test
    fun `an if-touched limit becomes a limit on touch and fills at the better of print and limit`() {
        PaperBroker(bus, clock, MarketPriceTracker()).submit(ifTouchedLimit(Side.BUY, trigger = "100", limit = "101"))

        tick("100.5", 1L)
        assertThat(fills).isEmpty()
        tick("99.5", 2L)

        assertThat(fills.single().clientOrderId).isEqualTo("it")
        assertThat(fills.single().price).isEqualByComparingTo("99.5")
        assertThat(fills.single().strategyId).isEqualTo("s1")
    }

    @Test
    fun `an if-touched limit not marketable on touch keeps resting as a limit`() {
        PaperBroker(bus, clock, MarketPriceTracker()).submit(ifTouchedLimit(Side.SELL, trigger = "100", limit = "102"))

        tick("100.5", 1L)
        assertThat(fills).isEmpty()
        tick("102.5", 2L)

        assertThat(fills.single().price).isEqualByComparingTo("102.5")
    }

    @Test
    fun `a sell stop limit marketable on activation fills at the higher of print and limit`() {
        PaperBroker(bus, clock, MarketPriceTracker()).submit(
            OrderRequest.StopLimit("sl", "X", Side.SELL, Money.of("1"), Money.of("100"), Money.of("99"), TimeInForce.GTC, 0L),
        )

        tick("99.5", 1L)

        assertThat(fills.single().price).isEqualByComparingTo("99.5")
    }

    @Test
    fun `on bars a stop limit whose limit the gap traded through fills at the limit`() {
        val broker = PaperBroker(bus, clock, MarketPriceTracker(), fillAtTriggerPrice = { true })
        tick("105", 1L)
        broker.submit(
            OrderRequest.StopLimit("sl", "X", Side.BUY, Money.of("1"), Money.of("100"), Money.of("101"), TimeInForce.GTC, 0L),
        )

        tick("100.5", 2L)

        assertThat(fills.single().price).isEqualByComparingTo("101")
    }

    @Test
    fun `a bracket is not an order type the paper broker books`() {
        val broker = PaperBroker(bus, clock, MarketPriceTracker())
        val entry = OrderRequest.Market("e", "X", Side.BUY, Money.of("1"), timeInForce = TimeInForce.GTC, timestamp = 0L)
        val bracket =
            OrderRequest.Bracket(
                "b",
                "X",
                Side.BUY,
                Money.of("1"),
                entry,
                Money.of("110"),
                StopLossSpec.Fixed(Money.of("90")),
                TimeInForce.GTC,
                0L,
            )

        assertThatThrownBy { broker.submit(bracket) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("PaperBroker received unexpected order type: Bracket")
    }
}
