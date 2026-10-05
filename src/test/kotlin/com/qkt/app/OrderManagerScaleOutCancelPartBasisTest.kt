package com.qkt.app

import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * qkt cancels a scale-out (basis plus one take-profit per leg, armed on the basis's fill) after its
 * basis filled in part (#1336). A plain cancel arms the legs for the filled part, as the venue ending
 * the remainder does; a close, which flattens the position itself, arms none; nothing filled, none.
 */
class OrderManagerScaleOutCancelPartBasisTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val fake = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
    private val cancelsAsked = mutableListOf<String>()
    private var venueAnswersAtOnce = false

    // A gateway answers a cancel later, on its stream: the wrapper is cancelled before the basis's end is heard.
    private val venue =
        object : Broker by fake {
            override fun cancel(orderId: String) {
                cancelsAsked += orderId
                if (venueAnswersAtOnce) fake.cancel(orderId)
            }
        }
    private val manager = OrderManager(venue, bus, MarketPriceTracker(), clock)
    private val basis =
        OrderRequest.Limit("e1", "X", Side.BUY, BigDecimal("2"), BigDecimal("100"), TimeInForce.GTC, 0L, "alpha")

    private fun fill(
        quantity: String,
        cumulative: String,
    ) = bus.publish(
        BrokerEvent.OrderPartiallyFilled(
            "e1",
            "position-9",
            "X",
            Side.BUY,
            BigDecimal("100"),
            BigDecimal(quantity),
            BigDecimal(cumulative),
            "alpha",
        ),
    )

    private fun venueEndsBasis() = bus.publish(BrokerEvent.OrderCancelled("e1", "v1", "cancelled", "alpha"))

    private fun legs() = listOf("s1-leg-0", "s1-leg-1").mapNotNull { manager.getOrder(it) }

    private fun assertLegsFor(quantity: String) {
        assertThat(legs()).hasSize(2)
        assertThat(legs()).allSatisfy { leg ->
            assertThat(leg.state).isEqualTo(OrderState.PENDING)
            assertThat(leg.request.quantity).isEqualByComparingTo(quantity)
            assertThat((leg.request as OrderRequest.IfTouched).closesTicket).isEqualTo("position-9")
        }
    }

    @Test
    fun `a strategy cancel of a scale-out whose basis partly filled arms the legs for the filled part`() {
        venueAnswersAtOnce = true
        manager.submit(scaleOut(basis, strategyId = "alpha"))
        fill("0.4", "0.4")

        manager.cancel("s1")

        assertLegsFor("0.2")
        assertThat(manager.getOrder("e1")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(manager.getOrder("s1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `everything filled before the venue confirms the cancel gets its legs`() {
        manager.submit(scaleOut(basis, strategyId = "alpha"))
        fill("0.4", "0.4")

        manager.cancelPendingForSymbol("X")
        fill("0.2", "0.6")
        assertThat(legs()).isEmpty()
        venueEndsBasis()

        assertThat(cancelsAsked).containsExactly("e1")
        assertLegsFor("0.3")
    }

    @Test
    fun `a risk halt cancels the remainder and arms the legs for the filled part`() {
        manager.submit(scaleOut(basis, strategyId = "alpha"))
        fill("0.4", "0.4")

        manager.cancelEntriesForHalt("alpha")
        venueEndsBasis()

        assertThat(cancelsAsked).containsExactly("e1")
        assertLegsFor("0.2")
    }

    @Test
    fun `a scale-out cancelled before anything filled arms no legs`() {
        manager.submit(scaleOut(basis, strategyId = "alpha"))

        manager.cancel("s1")
        venueEndsBasis()

        assertThat(legs()).isEmpty()
        assertThat(manager.activeEntryOrderCount("alpha", "X")).isZero()
    }

    @Test
    fun `a close cancels the remainder and arms no legs, since the close flattens the filled part`() {
        manager.submit(scaleOut(basis, strategyId = "alpha"))
        fill("0.4", "0.4")

        manager.closePendingForSymbol("X")
        venueEndsBasis()

        assertThat(cancelsAsked).contains("e1")
        assertThat(legs()).isEmpty()
        assertThat(manager.activeEntryOrderCount("alpha", "X")).isZero()
    }

    @Test
    fun `a cancel then a close before the venue answers arms no legs`() {
        manager.submit(scaleOut(basis, strategyId = "alpha"))
        fill("0.4", "0.4")

        manager.cancelPendingForSymbol("X")
        manager.closePendingForSymbol("X")
        venueEndsBasis()

        assertThat(legs()).isEmpty()
    }
}
