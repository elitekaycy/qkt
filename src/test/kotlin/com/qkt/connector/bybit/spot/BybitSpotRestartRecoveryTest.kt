package com.qkt.connector.bybit.spot

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.bybit.BybitDocPayloads.frame
import com.qkt.connector.bybit.BybitDocPayloads.page
import com.qkt.connector.bybit.BybitDocPayloads.wsExecution
import com.qkt.connector.bybit.BybitDocPayloads.wsOrder
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A restarted spot broker takes back a restored order by id: owned again, its booked fills never republished. */
class BybitSpotRestartRecoveryTest {
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val client = FakeBybitClient()
    private val events = mutableListOf<BrokerEvent>()
    private val first = wsExecution("c1", "e-a", "0.2", "0.3", "10.549011", category = "spot")
    private val last = wsExecution("c1", "e-b", "0.3", "0", "15.8235165", category = "spot")
    private val request =
        OrderRequest.Limit(
            "c1",
            "BYBIT_SPOT:BTCUSDT",
            Side.SELL,
            Money.of("0.5"),
            Money.of("94942.5"),
            TimeInForce.GTC,
            0L,
            "s1",
        )

    init {
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { events += it }
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
    }

    private fun restored(filled: String) =
        ManagedOrder(
            "c1",
            request,
            OrderState.PARTIALLY_FILLED,
            cumulativeFilledQuantity = Money.of(filled),
            createdAt = 0L,
            lastUpdatedAt = 0L,
        )

    private fun orderList(
        status: String,
        cum: String,
    ) = """{"retCode":0,"retMsg":"OK","result":{"list":[${wsOrder("c1", status, cum, category = "spot")}]}}"""

    @Test
    fun `fills made while down are booked once, the one booked before is not, and the venue's end follows`() {
        client.responses["/v5/execution/list"] = page(last, first)
        client.responses["/v5/order/history"] = orderList("Filled", "0.5")
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))

        val known = broker.recoverPendingOrders(listOf(restored("0.2")))

        assertThat(known).containsExactly("c1")
        assertThat(events.map { it::class.simpleName to (it as BrokerEvent.OrderEvent).strategyId })
            .containsExactly("OrderFilled" to "s1")
        assertThat((events.single() as BrokerEvent.OrderFilled).quantity).isEqualByComparingTo("0.3")
    }

    @Test
    fun `a restored open order is owned again, so its next execution is booked to its strategy`() {
        client.responses["/v5/order/realtime"] = orderList("PartiallyFilled", "0.2")
        client.responses["/v5/execution/list"] = page(first)
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))
        broker.recoverPendingOrders(listOf(restored("0.2")))

        client.emitWsFrame("execution", frame("execution", first, last))

        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>().map { it.strategyId to it.quantity })
            .containsExactly("s1" to Money.of("0.3"))
        assertThat(events.filterIsInstance<BrokerEvent.OrderPartiallyFilled>()).isEmpty()
    }

    @Test
    fun `an order Bybit no longer lists is left for the engine to retire`() {
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))

        assertThat(broker.recoverPendingOrders(listOf(restored("0")))).isEmpty()
        assertThat(events).isEmpty()
    }
}
