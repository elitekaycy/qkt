package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A decomposed bracket's entry filled 30 of 50 and was cancelled, so its exits cover 30. The venue
 * then reports more of the entry executed before the cancel took effect (#1349): the ledger books it,
 * so it gets the bracket's exits too, sized to it, and a repeat of that report arms nothing more.
 */
class OrderManagerBracketLateEntryExecutionTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val fake =
        FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
    private val venue =
        object : Broker by fake {
            override fun cancel(orderId: String) {}
        }
    private val manager = OrderManager(venue, bus, MarketPriceTracker(), clock)
    private val entry = OrderRequest.Market("e1", "X", Side.BUY, BigDecimal("50"), TimeInForce.GTC, 0L, "alpha")

    private fun partEntryEnded() {
        manager.submit(
            OrderRequest.Bracket(
                "b1",
                "X",
                Side.BUY,
                BigDecimal("50"),
                entry,
                BigDecimal("110"),
                StopLossSpec.Fixed(BigDecimal("90")),
                TimeInForce.GTC,
                0L,
                "alpha",
            ),
        )
        slice("30", "30")
        bus.publish(BrokerEvent.OrderCancelled("e1", "v1", "cancelled", "alpha"))
    }

    private fun slice(
        quantity: String,
        cumulative: String,
    ) = bus.publish(
        BrokerEvent.OrderPartiallyFilled(
            "e1",
            "v1",
            "X",
            Side.BUY,
            BigDecimal("100"),
            BigDecimal(quantity),
            BigDecimal(cumulative),
            "alpha",
        ),
    )

    private fun stops() = fake.submits.filter { it.id.endsWith("-sl") }

    private fun protectedQuantity() = stops().fold(BigDecimal.ZERO) { sum, it -> sum + it.quantity }

    @Test
    fun `a slice the venue reports after the entry's cancel is protected too`() {
        partEntryEnded()
        assertThat(stops().map { it.id }).containsExactly("b1-sl")

        slice("10", "40")

        assertThat(protectedQuantity()).isEqualByComparingTo("40")
        assertThat(stops().map { it.id }).containsExactly("b1-sl", "b1-late1-sl")
        assertThat((stops().last() as OrderRequest.Stop).stopPrice).isEqualByComparingTo("90")
    }

    @Test
    fun `a late full fill is protected even after the ended entry was reclaimed`() {
        partEntryEnded()
        bus.publish(TickEvent(Tick("X", BigDecimal("100"), 1L)))

        bus.publish(BrokerEvent.OrderFilled("e1", "v1", "X", Side.BUY, BigDecimal("100"), BigDecimal("20"), "alpha"))

        assertThat(protectedQuantity()).isEqualByComparingTo("50")
    }

    @Test
    fun `a repeated late slice arms nothing more`() {
        partEntryEnded()

        slice("10", "40")
        slice("10", "40")

        assertThat(protectedQuantity()).isEqualByComparingTo("40")
    }
}
