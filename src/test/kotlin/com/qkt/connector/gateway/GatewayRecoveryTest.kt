package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A restart: restored orders are resolved by id, never by a time window or a guess. */
internal class GatewayRecoveryTest : GatewayHarness() {
    private fun restored(
        id: String,
        quantity: String,
        filled: String,
    ) = ManagedOrder(
        id,
        market(id, "a", quantity = quantity),
        OrderState.WORKING,
        cumulativeFilledQuantity = BigDecimal(filled),
        createdAt = 5L,
        lastUpdatedAt = 5L,
    )

    @Test
    fun `a fill booked long before the restart is not booked again, and the one made while down is`() {
        val before = Strategy()
        val first = broker(session(), before, "a")
        first.submit(market("a-9", "a", quantity = "1"))
        await { before.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill(wire("a-9"), "f9", "0.5", "650", FakeGateway.TIME - 7_200_000) }
        await { before.of<BrokerEvent.OrderPartiallyFilled>().isNotEmpty() }
        first.shutdown()
        fake.act { fill(wire("a-9"), "f10", "0.5", "652", FakeGateway.TIME) }

        val after = Strategy()
        val known = broker(session(), after, "a").recoverPendingOrders(listOf(restored("a-9", "1", "0.5")), emptySet())

        await { after.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        assertThat(known).containsExactly("a-9")
        val filled = after.of<BrokerEvent.OrderFilled>().single()
        assertThat(filled.quantity to filled.price).isEqualTo(BigDecimal("0.5") to BigDecimal("652"))
        assertThat(after.of<BrokerEvent.OrderPartiallyFilled>()).isEmpty()
    }

    @Test
    fun `an order that ended while qkt was down ends in the engine, and one never placed is not accounted`() {
        val before = Strategy()
        broker(session(), before, "a").submit(market("a-1", "a"))
        await { before.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { cancel(wire("a-1")) }

        val after = Strategy()
        val known =
            broker(
                session(),
                after,
                "a",
            ).recoverPendingOrders(listOf(restored("a-1", "0.1", "0"), restored("a-2", "0.1", "0")), emptySet())

        assertThat(known).containsExactly("a-1")
        await { after.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
    }

    @Test
    fun `an order whose remainder was cancelled while qkt was down books its filled part, then ends`() {
        val before = Strategy()
        broker(session(), before, "a").submit(market("a-1", "a", quantity = "0.5"))
        await { before.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { cancelAfterFilling(wire("a-1"), "f1", "0.3", "650", pushFill = false) }

        val after = Strategy()
        broker(session(), after, "a").recoverPendingOrders(listOf(restored("a-1", "0.5", "0")), emptySet())

        await { after.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
        val trail = after.events.filterIsInstance<BrokerEvent.OrderEvent>().map { it::class.simpleName }
        assertThat(trail).containsExactly("OrderPartiallyFilled", "OrderCancelled")
        assertThat(after.of<BrokerEvent.OrderPartiallyFilled>().single().quantity).isEqualByComparingTo("0.3")
        assertThat(fake.submits).hasSize(1)
    }

    @Test
    fun `a contract still held that settled while qkt was down settles at its price once restored`() {
        fake.act {
            settle(
                WireSettlement(code, "1000", FakeGateway.TIME, listOf(WireCost("delivery_fee", "1", "USDC"))),
            )
        }
        val after = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val restoredBroker = broker(session(), after, "a")

        restoredBroker.watchBookedLegs { emptyList() }

        val settled = after.of<ContractSettled>().single()
        assertThat(settled.symbol to settled.price).isEqualTo(symbol to BigDecimal("1000"))
        assertThat(settled.costs).isEmpty()
    }

    @Test
    fun `an option leg the gateway no longer lists after its expiry still settles once restored`() {
        val expired = "BTC_USDC-26SEP26-90000-P"
        fake.act { settle(WireSettlement(expired, "250", FakeGateway.TIME)) }
        val after = Strategy().apply { held["DERIBIT:BTC_USDC_26SEP26_90000_P"] = BigDecimal("-0.1") }
        val restoredBroker = broker(session(), after, "a")

        restoredBroker.watchBookedLegs { emptyList() }

        assertThat(restoredBroker.isAccountWide("DERIBIT:BTC_USDC_26SEP26_90000_P")).isTrue()
        val settled = after.of<ContractSettled>().single()
        assertThat(settled.symbol to settled.price).isEqualTo("DERIBIT:BTC_USDC_26SEP26_90000_P" to BigDecimal("250"))
    }

    @Test
    fun `an engine id handed out again after a restart places a new order, never the ended one`() {
        val before = Strategy()
        broker(session(), before, "a").submit(market("a-1", "a"))
        await { before.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill(wire("a-1"), "f1", "0.1", "650", FakeGateway.TIME) }
        await { before.of<BrokerEvent.OrderFilled>().isNotEmpty() }

        val after = Strategy()
        broker(session(), after, "a").submit(market("a-1", "a").copy(timestamp = 9L))

        await { after.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        assertThat(fake.submits.map { it.clientOrderId }).containsExactly(wire("a-1"), "a-1.9")
        assertThat(after.of<BrokerEvent.OrderAccepted>().single().clientOrderId).isEqualTo("a-1")
        assertThat(after.of<BrokerEvent.OrderFilled>()).isEmpty()
    }
}
