package com.qkt.app

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
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * qkt cancels a decomposed bracket (entry plus held stop and target, as on a VGP gateway) as a whole
 * after its entry filled in part (#1328). A plain cancel keeps the filled part protected; a close,
 * which flattens the position itself, takes the exits away; nothing filled means no exits.
 */
class OrderManagerBracketCancelPartEntryTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val fake =
        FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
    private val cancelsAsked = mutableListOf<String>()
    private var venueAnswersAtOnce = false

    // A gateway answers a cancel later, on its stream: the bracket is cancelled before the entry's end is heard.
    private val venue =
        object : Broker by fake {
            override fun cancel(orderId: String) {
                cancelsAsked += orderId
                if (venueAnswersAtOnce) fake.cancel(orderId)
            }
        }
    private val manager = OrderManager(venue, bus, MarketPriceTracker(), clock)
    private val entry = OrderRequest.Market("e1", "X", Side.BUY, BigDecimal("50"), TimeInForce.GTC, 0L, "alpha")

    private fun submitBracket() =
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

    private fun fill(
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

    private fun venueEndsEntry() = bus.publish(BrokerEvent.OrderCancelled("e1", "v1", "cancelled", "alpha"))

    private fun exits() = fake.submits.filter { it.id != "e1" }

    private fun assertExitsFor(quantity: String) {
        assertThat(exits().map { it.id }).containsExactlyInAnyOrder("b1-sl", "b1-tp")
        assertThat(exits().map { it.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo(quantity) }
        assertThat((exits().single { it.id == "b1-sl" } as OrderRequest.Stop).stopPrice).isEqualByComparingTo("90")
    }

    @Test
    fun `a strategy cancel of a bracket whose entry partly filled keeps the filled part protected`() {
        venueAnswersAtOnce = true
        submitBracket()
        fill("30", "30")

        manager.cancel("b1")

        assertExitsFor("30")
        assertThat(manager.getOrder("e1")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(manager.getOrder("b1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `everything filled before the venue confirms the cancel is protected`() {
        submitBracket()
        fill("30", "30")

        manager.cancelPendingForSymbol("X")
        fill("10", "40")
        assertThat(exits()).isEmpty()
        venueEndsEntry()

        assertThat(cancelsAsked).containsExactly("e1")
        assertExitsFor("40")
    }

    @Test
    fun `a risk halt cancels the remainder and protects the filled part`() {
        submitBracket()
        fill("30", "30")

        manager.cancelEntriesForHalt("alpha")
        venueEndsEntry()

        assertThat(cancelsAsked).containsExactly("e1")
        assertExitsFor("30")
    }

    @Test
    fun `a bracket cancelled before anything filled arms no exits`() {
        submitBracket()

        manager.cancel("b1")
        venueEndsEntry()

        assertThat(exits()).isEmpty()
        assertThat(manager.getOrder("b1-oco")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(manager.activeEntryOrderCount("alpha", "X")).isZero()
    }

    @Test
    fun `an entry that fills whole before the cancel lands gets the exits for all of it`() {
        submitBracket()

        manager.cancel("b1")
        bus.publish(BrokerEvent.OrderFilled("e1", "v1", "X", Side.BUY, BigDecimal("100"), BigDecimal("50"), "alpha"))

        assertExitsFor("50")
    }

    @Test
    fun `a close cancels the remainder and drops the exits, since the close flattens the filled part`() {
        submitBracket()
        fill("30", "30")

        manager.closePendingForSymbol("X")
        venueEndsEntry()

        assertThat(cancelsAsked).contains("e1")
        assertThat(exits()).isEmpty()
        assertThat(manager.getOrder("b1-oco")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(manager.activeEntryOrderCount("alpha", "X")).isZero()
    }

    @Test
    fun `a cancel then a close before the venue answers leaves no exits`() {
        submitBracket()
        fill("30", "30")

        manager.cancelPendingForSymbol("X")
        manager.closePendingForSymbol("X")
        venueEndsEntry()

        assertThat(exits()).isEmpty()
        assertThat(manager.getOrder("b1-oco")?.state).isEqualTo(OrderState.CANCELLED)
    }
}
