package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.source.SymbolPattern
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A live session never holds the venue broker itself: it holds a [CompositeBroker] over it. A venue
 * capability the router does not forward does not exist as far as the session is concerned.
 */
class RoutedVenueOrderCancelTest {
    private val clock = FixedClock(0L)
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal? = BigDecimal.ONE
        }

    private inner class Venue(
        private val resting: MutableList<String>,
    ) : Broker by PaperBroker(EventBus(clock, MonotonicSequenceGenerator()), clock, prices),
        VenueOrderCancel {
        val cancelled = mutableListOf<String>()

        override fun pendingOrders(): List<BrokerPendingOrder> =
            resting.map {
                BrokerPendingOrder(
                    it,
                    "X",
                    Side.BUY,
                    "ORDER_TYPE_BUY_LIMIT",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                )
            }

        override fun cancelVenueOrder(ticket: String): Boolean {
            cancelled += ticket
            return resting.remove(ticket)
        }
    }

    @Test
    fun `the session's router sends a venue cancel to the leaf that is resting the ticket`() {
        val exness = Venue(mutableListOf("3260244759"))
        val icmarkets = Venue(mutableListOf("55"))
        val session: Broker =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("EXNESS:") to exness,
                        SymbolPattern.prefix("ICMARKETS:") to icmarkets,
                    ),
            )

        val done = (session as VenueOrderCancel).cancelVenueOrder("3260244759")

        assertThat(done).isTrue()
        assertThat(exness.cancelled).containsExactly("3260244759")
        assertThat(
            icmarkets.cancelled,
        ).`as`("the other venue may issue the same number for someone else's order").isEmpty()
    }

    @Test
    fun `a ticket no venue is resting is not cancelled anywhere`() {
        val exness = Venue(mutableListOf("1"))
        val session = CompositeBroker(routes = listOf(SymbolPattern.prefix("EXNESS:") to exness))

        assertThat((session as VenueOrderCancel).cancelVenueOrder("999")).isFalse()
        assertThat(exness.cancelled).isEmpty()
    }
}
