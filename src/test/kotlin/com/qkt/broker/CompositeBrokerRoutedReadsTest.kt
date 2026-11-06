package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.source.SymbolPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompositeBrokerRoutedReadsTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private class HedgingLeaf : Broker {
        override val name = "Hedging"
        override val capabilities: Set<OrderTypeCapability> = setOf(OrderTypeCapability.MARKET)

        override fun positionAccountingMode(symbol: String) = PositionAccountingMode.HEDGING

        override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) = Unit
    }

    @Test
    fun `routed symbol questions are answered by the leaf that trades the symbol`() {
        val leaf = HedgingLeaf()
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to leaf))

        assertThat(composite.capabilitiesFor("A:X")).containsExactly(OrderTypeCapability.MARKET)
        assertThat(composite.positionAccountingMode("A:X")).isEqualTo(PositionAccountingMode.HEDGING)
        assertThat(composite.marginVenueFor("A:X")).isSameAs(leaf)
        assertThat(composite.supports("A:X")).isTrue()
    }

    @Test
    fun `an unrouted symbol is answered by the fallback leaf`() {
        val fallback = HedgingLeaf()
        val routed = FakeBroker(bus, clock, emptySet())
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to routed), fallback)

        assertThat(composite.positionAccountingMode("Z:Y")).isEqualTo(PositionAccountingMode.HEDGING)
        assertThat(composite.marginVenueFor("Z:Y")).isSameAs(fallback)
    }

    @Test
    fun `a fill without a venue ticket routes no later position modification`() {
        val leaf = FakeBroker(bus, clock, emptySet())
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to leaf), bus = bus)
        val order = OrderRequest.Market("o1", "A:X", Side.BUY, Money.of("1"), TimeInForce.GTC, 0L)
        composite.submit(order)

        bus.publish(BrokerEvent.OrderFilled("o1", null, "A:X", Side.BUY, Money.of("10"), Money.of("1")))

        assertThat(composite.modifyPosition("o1", Money.of("9"), null).accepted).isFalse()
        assertThat(leaf.modifyPositions).isEmpty()
    }

    @Test
    fun `a composite without a fallback shuts down its routed leaves only`() {
        var shutdowns = 0
        val leaf =
            object : Broker by HedgingLeaf() {
                override fun shutdown() {
                    shutdowns++
                }
            }

        CompositeBroker(listOf(SymbolPattern.prefix("A:") to leaf)).shutdown()

        assertThat(shutdowns).isEqualTo(1)
    }
}
