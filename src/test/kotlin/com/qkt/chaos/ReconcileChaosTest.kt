package com.qkt.chaos

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.broker.bybit.spot.BybitSpotStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Chaos framing for broker recovery: unlike [com.qkt.broker.bybit.spot.BybitSpotStateRecoveryTest]
 * (which calls `reconcile()` directly), these drive recovery through the reconnect callback
 * (`onReconnect { reconcile() }` + `fireOnReconnect()`) — verifying the wiring that runs on a real
 * private-WebSocket reconnect, including dedup across repeated reconnects.
 */
class ReconcileChaosTest {
    private fun bus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private val emptyList = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun oneExecution(execId: String) =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc",""" +
            """"symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"$execId","category":"spot"}]}}"""

    private fun recovery(
        client: FakeBybitClient,
        bus: EventBus,
        known: Map<String, BybitSpotStateRecovery.ManagedOrderView>,
        seen: MutableSet<String>,
    ) = BybitSpotStateRecovery(
        transport = client,
        bus = bus,
        clock = FixedClock(1_000_000L),
        getKnownOrders = { known },
        lastFillTimeProvider = { 500_000L },
        seenExecIds = seen,
    )

    @Test
    fun `a fill missed while disconnected is replayed when the transport reconnects`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyList
        client.responses["/v5/execution/list"] = oneExecution("e1")
        val bus = bus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val rec = recovery(client, bus, emptyMap(), mutableSetOf())
        client.onReconnect { rec.reconcile() }

        client.fireOnReconnect()

        assertThat(fills.map { it.clientOrderId }).containsExactly("c1")
    }

    @Test
    fun `repeated reconnects replay the same fill exactly once`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyList
        client.responses["/v5/execution/list"] = oneExecution("e1")
        val bus = bus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val rec = recovery(client, bus, emptyMap(), mutableSetOf())
        client.onReconnect { rec.reconcile() }

        client.fireOnReconnect()
        client.fireOnReconnect()

        assertThat(fills).hasSize(1)
    }

    @Test
    fun `a locally-tracked order absent from the venue is cancelled on reconnect`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyList
        client.responses["/v5/execution/list"] = emptyList
        val bus = bus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        val rec =
            recovery(
                client,
                bus,
                mapOf("c1" to BybitSpotStateRecovery.ManagedOrderView("c1", "BYBIT_SPOT:BTCUSDT", Side.BUY)),
                mutableSetOf(),
            )
        client.onReconnect { rec.reconcile() }

        client.fireOnReconnect()

        assertThat(cancels.map { it.clientOrderId }).containsExactly("c1")
    }
}
