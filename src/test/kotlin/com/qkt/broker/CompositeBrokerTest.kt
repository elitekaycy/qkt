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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CompositeBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun marketReq(
        id: String,
        symbol: String,
    ) = OrderRequest.Market(
        id = id,
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `submit routes to broker matching symbol pattern`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to brokerA,
                        SymbolPattern.prefix("B:") to brokerB,
                    ),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        composite.submit(marketReq("c2", "B:Y"))

        assertThat(brokerA.submits.map { it.id }).containsExactly("c1")
        assertThat(brokerB.submits.map { it.id }).containsExactly("c2")
    }

    @Test
    fun `unmatched symbol routes to fallback`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val fallback = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                fallback = fallback,
                bus = bus,
            )

        composite.submit(marketReq("c1", "Z:UNMATCHED"))

        assertThat(brokerA.submits).isEmpty()
        assertThat(fallback.submits.map { it.id }).containsExactly("c1")
    }

    @Test
    fun `unmatched symbol with no fallback returns rejected SubmitAck`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                fallback = null,
                bus = bus,
            )

        val ack = composite.submit(marketReq("c1", "Z:UNMATCHED"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("no broker")
    }

    @Test
    fun `cancel routes to the broker that accepted the order`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to brokerA,
                        SymbolPattern.prefix("B:") to brokerB,
                    ),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        composite.cancel("c1")

        assertThat(brokerA.cancels).containsExactly("c1")
        assertThat(brokerB.cancels).isEmpty()
    }

    @Test
    fun `capabilitiesFor delegates to the routed sub-broker`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.STOP))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to brokerA,
                        SymbolPattern.prefix("B:") to brokerB,
                    ),
                bus = bus,
            )

        assertThat(composite.capabilitiesFor("A:X")).contains(OrderTypeCapability.LIMIT)
        assertThat(composite.capabilitiesFor("A:X")).doesNotContain(OrderTypeCapability.STOP)
        assertThat(composite.capabilitiesFor("B:Y")).contains(OrderTypeCapability.STOP)
        assertThat(composite.capabilitiesFor("B:Y")).doesNotContain(OrderTypeCapability.LIMIT)
        assertThat(composite.capabilitiesFor("UNMATCHED")).isEmpty()
    }

    @Test
    fun `flat capabilities throws because composite is symbol-dependent`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                bus = bus,
            )
        assertThatThrownBy { composite.capabilities }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `first matching route wins`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("X:") to brokerA,
                        SymbolPattern.prefix("X:") to brokerB,
                    ),
                bus = bus,
            )

        composite.submit(marketReq("c1", "X:Y"))

        assertThat(brokerA.submits).hasSize(1)
        assertThat(brokerB.submits).isEmpty()
    }

    @Test
    fun `terminal events prune the orderId-to-broker map`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "A:X",
                side = Side.BUY,
                price = Money.of("1"),
                quantity = Money.of("1"),
            ),
        )

        composite.cancel("c1")
        assertThat(brokerA.cancels).isEmpty()
    }

    @Test
    fun `getOpenPositions merges positions across leaves`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET)).apply {
                stubOpenPositions =
                    mapOf("A:X" to listOf(com.qkt.positions.Position("A:X", Money.of("1"), Money.of("100"))))
            }
        val brokerB =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET)).apply {
                stubOpenPositions =
                    mapOf("B:Y" to listOf(com.qkt.positions.Position("B:Y", Money.of("2"), Money.of("200"))))
            }
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA, SymbolPattern.prefix("B:") to brokerB),
                bus = bus,
            )

        val all = composite.getOpenPositions()

        assertThat(all.keys).containsExactlyInAnyOrder("A:X", "B:Y")
        assertThat(all["A:X"]!!.single().avgEntryPrice).isEqualByComparingTo("100")
    }

    @Test
    fun `recoverPendingOrders routes each order to its owning leaf and restores cancel routing`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA, SymbolPattern.prefix("B:") to brokerB),
                bus = bus,
            )
        val orderA =
            com.qkt.execution.ManagedOrder(
                "c1",
                marketReq("c1", "A:X"),
                com.qkt.execution.OrderState.WORKING,
                createdAt = 0L,
                lastUpdatedAt = 0L,
            )
        val orderB =
            com.qkt.execution.ManagedOrder(
                "c2",
                marketReq("c2", "B:Y"),
                com.qkt.execution.OrderState.WORKING,
                createdAt = 0L,
                lastUpdatedAt = 0L,
            )

        composite.recoverPendingOrders(listOf(orderA, orderB))

        assertThat(brokerA.recovered.map { it.id }).containsExactly("c1")
        assertThat(brokerB.recovered.map { it.id }).containsExactly("c2")

        // Routing is restored: a later cancel of a recovered order reaches the right leaf.
        composite.cancel("c1")
        assertThat(brokerA.cancels).containsExactly("c1")
        assertThat(brokerB.cancels).isEmpty()
    }

    @Test
    fun `modifyPosition routes to the leaf that filled the ticket`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA, SymbolPattern.prefix("B:") to brokerB),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        // Leaf A fills the order; the venue ticket "T1" is now owned by A.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "T1",
                symbol = "A:X",
                side = Side.BUY,
                price = Money.of("1"),
                quantity = Money.of("1"),
            ),
        )

        val ack = composite.modifyPosition("T1", sl = Money.of("0.9"), tp = Money.of("1.2"))

        assertThat(ack.accepted).isTrue
        assertThat(brokerA.modifyPositions.map { it.ticket }).containsExactly("T1")
        assertThat(brokerB.modifyPositions).isEmpty()
    }

    @Test
    fun `modifyPosition rejects an unknown ticket instead of falsely succeeding`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite = CompositeBroker(routes = listOf(SymbolPattern.prefix("A:") to brokerA), bus = bus)

        val ack = composite.modifyPosition("UNKNOWN", sl = Money.of("1"), tp = null)

        assertThat(ack.accepted).isFalse
        assertThat(brokerA.modifyPositions).isEmpty()
    }
}
