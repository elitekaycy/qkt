package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
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

    @Test
    fun `armed trail wrapper completes when its held engine stop fires and fills`() {
        // The engine-held stop fires a close-by-ticket and the venue fills it. That fill is the
        // position's exit exactly like a venue-side close is: the wrapper must complete and
        // release its exposure instead of staying pending until the next restart retires it.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            bracket(StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10"))),
        )
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        assertThat(om.activeOrders().map { it.id }).containsExactlyInAnyOrder("b1", "b1-sl")

        // Arm (MFE 10 ≥ threshold → trail at 110−5=105), then drop through the trail.
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))
        val close = broker.submits.first { it.id == "b1-sl" }
        broker.emitFill(close, price = Money.of("104"))

        assertThat(om.activeOrders()).isEmpty()
    }

    @Test
    fun `restoring a persisted attached armed-trail bracket before any quote anchors its stop on the replayed fill`() {
        // Live 2026-09-14 (pr-live-007 restart): a Market-entry bracket with an engine-managed
        // stop was persisted while its position was open. On restart the strategy failed to
        // deploy with "Cannot estimate entry price ... no last price" and retried forever,
        // because the quote only starts once the strategy is deployed.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
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
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor = persistor)

        om.restore(listOf("alpha"))
        assertThat(om.activeOrders().map { it.id }).contains("e1")

        // Venue recovery replays the open position as the entry's fill; the stop anchors on it.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "e1",
                brokerOrderId = "tkt-1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("1"),
                strategyId = "alpha",
                timestamp = clock.now(),
            ),
        )
        assertThat(om.activeOrders().map { it.id }).contains("b1-sl")
    }

    /** Recovery matched every restored order to its venue ticket that the ledger already booked. */
    private class BookedRecoveryBroker(
        private val delegate: FakeBroker,
        private val bus: EventBus,
        private val clock: FixedClock,
        private val ticketFor: (String) -> String?,
    ) : Broker by delegate {
        override fun recoverPendingOrders(
            orders: List<ManagedOrder>,
            bookedTickets: Set<String>,
        ): Set<String> {
            for (o in orders) {
                val ticket = ticketFor(o.id) ?: continue
                bus.publish(
                    BrokerEvent.OrderAccepted(
                        clientOrderId = o.id,
                        brokerOrderId = ticket,
                        strategyId = o.request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
            return orders.mapTo(LinkedHashSet()) { it.id }
        }
    }

    @Test
    fun `restored attached entry backed by a booked position is filled, not an open entry order`() {
        // Live 2026-09-14 (pr-live-007 restart over an open BTCUSD position): the restored entry
        // stayed WORKING with its exposure registered, so OPEN_ORDERS never returned to zero
        // after the position closed and the strategy could not re-enter until the next restart.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val fake = FakeBroker(bus, clock, attachCaps)
        val broker = BookedRecoveryBroker(fake, bus, clock) { id -> if (id == "e1") "tkt-1" else null }
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
