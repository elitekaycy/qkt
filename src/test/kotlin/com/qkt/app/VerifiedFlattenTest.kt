package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerPendingOrder
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.observe.insights.TicketAttribution
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VerifiedFlattenTest {
    private val clock = FixedClock(time = 1_789_996_000_000L)
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal? = BigDecimal("85000")
        }

    private fun resting(
        ticket: String,
        clientOrderId: String,
    ) = BrokerPendingOrder(
        ticket = ticket,
        symbol = "EXNESS:BTCUSD",
        side = Side.BUY,
        orderType = "ORDER_TYPE_BUY_LIMIT",
        qty = BigDecimal("0.01"),
        price = BigDecimal("82803.47"),
        clientOrderId = clientOrderId,
    )

    /** A venue with no positions and [orders] resting; [refuses] tickets survive a cancel. */
    private inner class Venue(
        orders: List<BrokerPendingOrder>,
        private val refuses: Set<String> = emptySet(),
    ) : Broker by PaperBroker(EventBus(clock, com.qkt.common.MonotonicSequenceGenerator()), clock, prices),
        com.qkt.broker.VenueOrderCancel {
        val book = orders.toMutableList()
        val cancelled = mutableListOf<String>()

        override val supportsPositionTickets = true

        override fun pendingOrders(): List<BrokerPendingOrder> = book.toList()

        override fun cancelVenueOrder(ticket: String): Boolean {
            cancelled += ticket
            return if (ticket in refuses) false else book.removeIf { it.ticket == ticket }
        }
    }

    private fun flatten(venue: Venue) =
        VerifiedFlatten(
            venue,
            TicketAttribution(),
            clock,
            listOf("atto_flatten_clean"),
            engineFlatten = {},
            pollMs = 1L,
        ).run(Duration.ofMillis(200))

    @Test
    fun `a resting order the engine never learned about is cancelled by its venue ticket`() {
        // The placement response was lost: the venue holds ticket 3260244759, the engine has no record.
        val venue = Venue(listOf(resting("3260244759", "dsl-atto_flatten_clean--2")))

        val result = flatten(venue)

        assertThat(venue.cancelled).containsExactly("3260244759")
        assertThat(result.verifiedFlat).isTrue()
    }

    @Test
    fun `another strategy's resting order is left alone`() {
        val venue = Venue(listOf(resting("77", "dsl-someone_else--0")))

        val result = flatten(venue)

        assertThat(venue.cancelled).isEmpty()
        assertThat(result.verifiedFlat).isTrue()
    }

    @Test
    fun `an order the venue will not cancel means the flatten is not verified, and says which`() {
        val venue = Venue(listOf(resting("3260244759", "dsl-atto_flatten_clean--2")), refuses = setOf("3260244759"))

        val result = flatten(venue)

        assertThat(result.verifiedFlat).isFalse()
        assertThat(result.remainingTickets).containsExactly("3260244759")
        assertThat(result.detail).contains("resting orders 3260244759")
    }
}
