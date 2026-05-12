package com.qkt.broker.bybit.spot

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotBrokerReconnectTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `reconnect reconciles missed fills via execution list`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        client.responses["/v5/order/realtime"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
        client.responses["/v5/execution/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}]}}"""

        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val broker = BybitSpotBroker(client, bus, FixedClock(1_000_000L))

        broker.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        client.fireOnReconnect()

        assertThat(fills).hasSize(1)
        assertThat(fills.single().clientOrderId).isEqualTo("c1")
        assertThat(fills.single().symbol).isEqualTo("BYBIT_SPOT:BTCUSDT")
    }

    @Test
    fun `reconnect with order still in open list does not emit Cancelled`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        client.responses["/v5/order/realtime"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","orderStatus":"New","category":"spot"}]}}"""
        client.responses["/v5/execution/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        val broker = BybitSpotBroker(client, bus, FixedClock(1_000_000L))

        broker.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        client.fireOnReconnect()

        assertThat(cancels).isEmpty()
    }

    @Test
    fun `live execution after reconnect does not double-fire when execId already in seenExecIds`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        client.responses["/v5/order/realtime"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
        client.responses["/v5/execution/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}]}}"""

        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val broker = BybitSpotBroker(client, bus, FixedClock(1_000_000L))

        broker.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        client.fireOnReconnect()
        assertThat(fills).hasSize(1)

        val liveFrame =
            Json
                .parseToJsonElement(
                    """{"topic":"execution","data":[{"orderLinkId":"c1","orderId":"abc","symbol":"BTCUSDT","side":"Buy","execPrice":"80000","execQty":"0.01","execId":"e1","category":"spot"}]}""",
                ).jsonObject
        client.emitWsFrame("execution", liveFrame)

        assertThat(fills).hasSize(1)
    }
}
