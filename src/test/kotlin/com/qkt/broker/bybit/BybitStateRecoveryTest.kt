package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitStateRecoveryTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun emptyOpenOrdersResponse() = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun emptyExecutionsResponse() = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    @Test
    fun `reconcile with empty Bybit state and empty engine state emits nothing`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()

        val bus = newBus()
        val emitted = mutableListOf<BrokerEvent>()
        bus.subscribe<BrokerEvent.OrderFilled> { emitted.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { emitted.add(it) }

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(1_000_000L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 500_000L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `reconcile emits OrderCancelled for engine-known orders missing from Bybit's open list`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()

        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }

        val knownOrders =
            mapOf(
                "c1" to BybitStateRecovery.ManagedOrderView("c1", "BYBIT_SPOT:BTCUSDT", Side.BUY),
                "c2" to BybitStateRecovery.ManagedOrderView("c2", "BYBIT_SPOT:BTCUSDT", Side.SELL),
            )
        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(1_000_000L),
                getKnownOrders = { knownOrders },
                lastFillTimeProvider = { 500_000L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(cancels.map { it.clientOrderId }).containsExactlyInAnyOrder("c1", "c2")
        assertThat(cancels.first().reason).contains("recovered")
    }

    @Test
    fun `reconcile does NOT emit Cancelled for orders still in Bybit's open list`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","orderStatus":"New","category":"spot"}]}}"""
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()

        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }

        val knownOrders =
            mapOf(
                "c1" to BybitStateRecovery.ManagedOrderView("c1", "BYBIT_SPOT:BTCUSDT", Side.BUY),
            )
        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(1_000_000L),
                getKnownOrders = { knownOrders },
                lastFillTimeProvider = { 500_000L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(cancels).isEmpty()
    }

    @Test
    fun `reconcile emits OrderFilled for executions in Bybit's list since lastFillTime`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}]}}"""

        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(1_000_000L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 500_000L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(fills).hasSize(1)
        assertThat(fills.single().clientOrderId).isEqualTo("c1")
        assertThat(fills.single().symbol).isEqualTo("BYBIT_SPOT:BTCUSDT")
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("80000"))
    }

    @Test
    fun `reconcile dedups executions by execId via seenExecIds`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}]}}"""

        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }

        val seenExecIds = mutableSetOf("e1")
        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(1_000_000L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 500_000L },
                seenExecIds = seenExecIds,
            )
        recovery.reconcile()

        assertThat(fills).isEmpty()
    }

    @Test
    fun `reconcile uses startTime equal to lastFillTime minus 60s`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = newBus(),
                clock = FixedClock(0L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 1_000_000L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        val executionPost = client.posts.first { it.path == "/v5/execution/list" }
        assertThat(executionPost.body).contains("\"startTime\":940000")
    }

    @Test
    fun `reconcile fetches wallet balance and writes it to the transport cache`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()
        client.responses["/v5/account/wallet-balance"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","coin":[{"coin":"BTC","walletBalance":"0.5"},{"coin":"USDT","walletBalance":"30000"}]}]}}"""

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = newBus(),
                clock = FixedClock(0L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(client.balances).containsOnlyKeys("BTC", "USDT")
        assertThat(client.balances["BTC"]).isEqualByComparingTo(java.math.BigDecimal("0.5"))
    }

    @Test
    fun `reconcile publishes BalancesUpdated event with source BYBIT_SPOT`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()
        client.responses["/v5/account/wallet-balance"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","coin":[{"coin":"BTC","walletBalance":"0.5"}]}]}}"""

        val bus = newBus()
        val received = mutableListOf<BrokerEvent.BalancesUpdated>()
        bus.subscribe<BrokerEvent.BalancesUpdated> { received.add(it) }

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = bus,
                clock = FixedClock(1_234_567L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        assertThat(received).hasSize(1)
        assertThat(received.single().source).isEqualTo("BYBIT_SPOT")
        assertThat(received.single().balances).containsKey("BTC")
    }

    @Test
    fun `reconcile sends accountType UNIFIED in wallet-balance request body`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()
        client.responses["/v5/account/wallet-balance"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = newBus(),
                clock = FixedClock(0L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        val balancePost = client.posts.first { it.path == "/v5/account/wallet-balance" }
        assertThat(balancePost.body).contains("\"accountType\":\"UNIFIED\"")
    }

    @Test
    fun `concurrent calls to reconcile are serialized`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()
        client.responses["/v5/account/wallet-balance"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val recovery =
            BybitStateRecovery(
                transport = client,
                bus = newBus(),
                clock = FixedClock(0L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )

        val threads =
            (1..8).map {
                Thread { recovery.reconcile() }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(client.posts).hasSize(24)
    }
}
