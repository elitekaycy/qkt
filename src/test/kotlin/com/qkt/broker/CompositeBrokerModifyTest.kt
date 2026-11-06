package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.source.SymbolPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompositeBrokerModifyTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private fun market(
        id: String,
        symbol: String,
    ) = OrderRequest.Market(id, symbol, Side.BUY, Money.of("1"), timeInForce = TimeInForce.GTC, timestamp = 0L)

    private class ModifyingBroker(
        override val name: String,
    ) : Broker {
        override val capabilities: Set<OrderTypeCapability> = emptySet()
        val modified = mutableListOf<Pair<String, OrderModification>>()
        var shutdowns = 0
        var failShutdown = false

        override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) = Unit

        override fun modify(
            orderId: String,
            changes: OrderModification,
        ): SubmitAck {
            modified += orderId to changes
            return SubmitAck(orderId, "venue-$orderId", accepted = true)
        }

        override fun shutdown() {
            shutdowns += 1
            if (failShutdown) error("$name shutdown failed")
        }
    }

    @Test
    fun `modify reaches the leaf that took the order`() {
        val a = ModifyingBroker("A")
        val b = ModifyingBroker("B")
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to a, SymbolPattern.prefix("B:") to b), bus = bus)
        composite.submit(market("o1", "B:X"))
        val change = OrderModification(newLimitPrice = Money.of("5"))

        val ack = composite.modify("o1", change)

        assertThat(ack).isEqualTo(SubmitAck("o1", "venue-o1", accepted = true))
        assertThat(b.modified).containsExactly("o1" to change)
        assertThat(a.modified).isEmpty()
    }

    @Test
    fun `modify of an order no leaf took is refused`() {
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to ModifyingBroker("A")))

        val ack = composite.modify("ghost", OrderModification(newQuantity = Money.of("1")))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.brokerOrderId).isNull()
        assertThat(ack.rejectReason).isEqualTo("unknown orderId ghost")
    }

    @Test
    fun `async position modification of an unknown ticket reports a refusal`() {
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to FakeBroker(bus, clock, emptySet())), bus = bus)
        val results = mutableListOf<SubmitAck>()

        composite.modifyPositionAsync("T9", sl = Money.of("1"), tp = null) { results += it }

        assertThat(results).containsExactly(SubmitAck("T9", "T9", accepted = false, rejectReason = "no leaf owns ticket T9"))
    }

    @Test
    fun `async position modification reaches the leaf that filled the ticket`() {
        val a = FakeBroker(bus, clock, emptySet())
        val b = FakeBroker(bus, clock, emptySet())
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to a, SymbolPattern.prefix("B:") to b), bus = bus)
        val order = market("o2", "B:X")
        composite.submit(order)
        b.emitFill(order, Money.of("10"))
        val results = mutableListOf<SubmitAck>()

        composite.modifyPositionAsync("o2", sl = Money.of("9"), tp = Money.of("12")) { results += it }

        assertThat(results.single().accepted).isTrue()
        assertThat(b.modifyPositions).containsExactly(FakeBroker.ModifyPositionCall("o2", Money.of("9"), Money.of("12")))
        assertThat(a.modifyPositions).isEmpty()
    }

    @Test
    fun `shutdown reaches every leaf and the fallback even when one leaf fails`() {
        val a = ModifyingBroker("A").apply { failShutdown = true }
        val b = ModifyingBroker("B")
        val fallback = ModifyingBroker("F").apply { failShutdown = true }
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to a, SymbolPattern.prefix("B:") to b), fallback)

        composite.shutdown()

        assertThat(listOf(a.shutdowns, b.shutdowns, fallback.shutdowns)).containsExactly(1, 1, 1)
    }

    @Test
    fun `symbol-routed questions answer for an unrouted symbol without a fallback`() {
        val composite = CompositeBroker(listOf(SymbolPattern.prefix("A:") to ModifyingBroker("A")))

        assertThat(composite.isAccountWide("Z:Y")).isFalse()
        assertThat(composite.acceptsOrders("Z:Y", 0L)).isTrue()
        assertThat(composite.marginVenueFor("Z:Y")).isSameAs(composite)
        assertThat(composite.positionAccountingMode("Z:Y")).isEqualTo(PositionAccountingMode.UNKNOWN)
        assertThat(composite.capabilitiesFor("Z:Y")).isEmpty()
        assertThat(composite.supportsPositionTickets).isFalse()
        assertThat(CompositeBroker(emptyList()).supportsPositionTickets).isFalse()
    }
}
