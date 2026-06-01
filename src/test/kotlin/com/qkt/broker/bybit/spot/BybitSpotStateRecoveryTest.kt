package com.qkt.broker.bybit.spot

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotStateRecoveryTest {
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
            BybitSpotStateRecovery(
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
                "c1" to BybitSpotStateRecovery.ManagedOrderView("c1", "BYBIT_SPOT:BTCUSDT", Side.BUY),
                "c2" to BybitSpotStateRecovery.ManagedOrderView("c2", "BYBIT_SPOT:BTCUSDT", Side.SELL),
            )
        val recovery =
            BybitSpotStateRecovery(
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
                "c1" to BybitSpotStateRecovery.ManagedOrderView("c1", "BYBIT_SPOT:BTCUSDT", Side.BUY),
            )
        val recovery =
            BybitSpotStateRecovery(
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
            BybitSpotStateRecovery(
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
            BybitSpotStateRecovery(
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
    fun `reconcile follows nextPageCursor across multiple execution-list pages`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/account/wallet-balance"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
        client.responsesByPredicate.add(
            { path: String, body: String -> path == "/v5/execution/list" && !body.contains("\"cursor\"") } to
                """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"a","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}],"nextPageCursor":"page2"}}""",
        )
        client.responsesByPredicate.add(
            { path: String, body: String -> path == "/v5/execution/list" && body.contains("\"cursor\":\"page2\"") } to
                """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c2","orderId":"b","symbol":"BTCUSDT","side":"Sell","execPrice":"81000","execQty":"0.01","execId":"e2","category":"spot"}],"nextPageCursor":""}}""",
        )

        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }

        BybitSpotStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(fills).hasSize(2)
        assertThat(fills.map { it.clientOrderId }).containsExactly("c1", "c2")
    }

    @Test
    fun `reconcile caps execution pagination at MAX_EXECUTIONS_PER_RECONCILE`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/account/wallet-balance"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
        val pageCounter =
            java.util.concurrent.atomic
                .AtomicInteger()
        client.dynamicResponses.add(
            { path: String, _: String -> path == "/v5/execution/list" } to {
                val n = pageCounter.incrementAndGet()
                buildString {
                    append("""{"retCode":0,"retMsg":"OK","result":{"list":[""")
                    for (i in 1..50) {
                        if (i > 1) append(",")
                        append(
                            """{"orderLinkId":"c-$n-$i","orderId":"o","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e-$n-$i","category":"spot"}""",
                        )
                    }
                    append("""],"nextPageCursor":"more"}}""")
                }
            },
        )

        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }

        BybitSpotStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        ).reconcile()

        assertThat(fills.size).isEqualTo(BybitSpotStateRecovery.MAX_EXECUTIONS_PER_RECONCILE)
    }

    @Test
    fun `reconcile uses startTime equal to lastFillTime minus 60s`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()

        val recovery =
            BybitSpotStateRecovery(
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
            BybitSpotStateRecovery(
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
            BybitSpotStateRecovery(
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
    fun `reconcile sends accountType UNIFIED in the wallet-balance query`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()
        client.responses["/v5/account/wallet-balance"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val recovery =
            BybitSpotStateRecovery(
                transport = client,
                bus = newBus(),
                clock = FixedClock(0L),
                getKnownOrders = { emptyMap() },
                lastFillTimeProvider = { 0L },
                seenExecIds = mutableSetOf(),
            )
        recovery.reconcile()

        // GET query form (was a POST JSON body before the GET fix); a revert to POST
        // would serialize {"accountType":...} and fail this assertion.
        val balanceCall = client.posts.first { it.path == "/v5/account/wallet-balance" }
        assertThat(balanceCall.body).contains("accountType=UNIFIED")
    }

    @Test
    fun `concurrent calls to reconcile are serialized`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
        client.responses["/v5/execution/list"] = emptyExecutionsResponse()
        client.responses["/v5/account/wallet-balance"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val recovery =
            BybitSpotStateRecovery(
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
