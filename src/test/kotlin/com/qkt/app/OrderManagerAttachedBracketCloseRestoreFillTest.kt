package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.attachCaps
import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.newBus
import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.venueClose
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.NoopStatePersistor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerAttachedBracketCloseRestoreFillTest {
    /**
     * Recovery matched every restored order to its venue ticket that the ledger already booked.
     * [deferAccepted] models the daemon, where the accepted event lands on the engine thread
     * after restore() has returned; the test then publishes the deferred events itself.
     */
    private class BookedRecoveryBroker(
        private val delegate: FakeBroker,
        private val bus: EventBus,
        private val clock: FixedClock,
        private val deferAccepted: Boolean,
        private val ticketFor: (String) -> String?,
    ) : Broker by delegate {
        val deferred = mutableListOf<BrokerEvent.OrderAccepted>()

        override fun recoverPendingOrders(
            orders: List<ManagedOrder>,
            bookedTickets: Set<String>,
        ): Set<String> {
            for (o in orders) {
                val ticket = ticketFor(o.id) ?: continue
                val accepted =
                    BrokerEvent.OrderAccepted(
                        clientOrderId = o.id,
                        brokerOrderId = ticket,
                        strategyId = o.request.strategyId,
                        timestamp = clock.now(),
                    )
                if (deferAccepted) deferred += accepted else bus.publish(accepted)
            }
            return orders.mapTo(LinkedHashSet()) { it.id }
        }
    }

    private fun persistedAttachedBracket(): OrderRequest.Bracket =
        OrderRequest.Bracket(
            id = "b1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry =
                OrderRequest.Market(
                    id = "e1",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                    strategyId = "alpha",
                ),
            takeProfit = Money.of("120"),
            stopLoss = StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10")),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    @Test
    fun `restored attached entry whose ticket arrives after restore returns is still marked filled`() {
        // Daemon ordering (live 2026-09-14 21:41 UTC): recovery runs on the main thread and the
        // accepted event with the ticket is delivered on the engine thread afterwards.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val fake = FakeBroker(bus, clock, attachCaps)
        val broker =
            BookedRecoveryBroker(fake, bus, clock, deferAccepted = true) { id ->
                if (id ==
                    "e1"
                ) {
                    "tkt-1"
                } else {
                    null
                }
            }
        val persistor = NoopStatePersistor()
        persistor.savePendingOrders("alpha", mapOf("b1" to persistedAttachedBracket()))
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor = persistor,
                bookedVenueTickets = { setOf("tkt-1") },
            )

        om.restore(listOf("alpha"))
        assertThat(om.activeEntryOrderCount("alpha", "X")).isEqualTo(1)
        broker.deferred.forEach { bus.publish(it) }

        assertThat(om.activeEntryOrderCount("alpha", "X")).isZero()
        assertThat(om.activeOrders().map { it.id }).doesNotContain("e1")
    }

    @Test
    fun `restored attached entry backed by a booked position is filled, not an open entry order`() {
        // Live 2026-09-14 (pr-live-007 restart over an open BTCUSD position): the restored entry
        // stayed WORKING with its exposure registered, so OPEN_ORDERS never returned to zero
        // after the position closed and the strategy could not re-enter until the next restart.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val fake = FakeBroker(bus, clock, attachCaps)
        val broker =
            BookedRecoveryBroker(fake, bus, clock, deferAccepted = false) { id ->
                if (id ==
                    "e1"
                ) {
                    "tkt-1"
                } else {
                    null
                }
            }
        val persistor = NoopStatePersistor()
        val request =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry =
                    OrderRequest.Market(
                        id = "e1",
                        symbol = "X",
                        side = Side.BUY,
                        quantity = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                        strategyId = "alpha",
                    ),
                takeProfit = Money.of("120"),
                stopLoss = StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf("b1" to request))
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor = persistor,
                bookedVenueTickets = { setOf("tkt-1") },
            )

        om.restore(listOf("alpha"))

        assertThat(om.activeEntryOrderCount("alpha", "X")).isZero()
        assertThat(om.activeOrders().map { it.id }).doesNotContain("e1")

        // The venue later closes that position: nothing is left over and nothing throws.
        venueClose(bus, clock, Money.of("1"))
        assertThat(om.activeEntryOrderCount("alpha", "X")).isZero()
        assertThat(om.activeOrders()).isEmpty()
    }
}
