package com.qkt.broker.bybit

import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `name capabilities and supports`() {
        val client = FakeBybitClient()
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }
        assertThat(broker.name).isEqualTo("BybitSpot")
        assertThat(broker.capabilities).contains(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )
        assertThat(broker.supports("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(broker.supports("OANDA:EURUSD")).isFalse()
    }

    @Test
    fun `submit Market posts to v5 order create with correct body`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc-123","orderLinkId":"c1"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c1",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(client.posts).hasSize(1)
        assertThat(client.posts.single().path).isEqualTo("/v5/order/create")
        assertThat(client.posts.single().body).contains("\"category\":\"spot\"")
        assertThat(ack.accepted).isTrue()
        assertThat(ack.brokerOrderId).isEqualTo("abc-123")
    }

    @Test
    fun `submit Limit returns accepted=true on retCode 0`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc-456","orderLinkId":"c2"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }

        val ack =
            broker.submit(
                OrderRequest.Limit(
                    id = "c2",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    limitPrice = Money.of("80000"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(ack.brokerOrderId).isEqualTo("abc-456")
    }

    @Test
    fun `submit non-zero retCode produces accepted=false and OrderRejected`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":10001,"retMsg":"params error","result":{}}"""
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { e -> rejects.add(e) }
        val broker = BybitSpotBroker(client, bus, FixedClock(0L)).also { client.posts.clear() }

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c3",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("10001")
        assertThat(rejects).hasSize(1)
        assertThat(rejects.single().reason).contains("params error")
    }

    @Test
    fun `submit Stop posts a body containing triggerPrice and triggerDirection`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc-stop","orderLinkId":"c-stop"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }

        broker.submit(
            OrderRequest.Stop(
                id = "c-stop",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("79000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(client.posts.single().body).contains("\"triggerPrice\":\"79000")
        assertThat(client.posts.single().body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `submit StopLimit posts a body with both triggerPrice and price`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }

        broker.submit(
            OrderRequest.StopLimit(
                id = "c",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("85000"),
                limitPrice = Money.of("85100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        val body = client.posts.single().body
        assertThat(body).contains("\"triggerPrice\":\"85000")
        assertThat(body).contains("\"price\":\"85100")
    }

    @Test
    fun `submit IfTouched MARKET posts triggerDirection 2 for BUY (fall to trigger)`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }

        broker.submit(
            OrderRequest.IfTouched(
                id = "c",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                triggerPrice = Money.of("75000"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        val body = client.posts.single().body
        assertThat(body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `cancel posts to v5 order cancel with orderLinkId`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/cancel"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }
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

        broker.cancel("c1")

        assertThat(client.posts).hasSize(2)
        val cancelPost = client.posts[1]
        assertThat(cancelPost.path).isEqualTo("/v5/order/cancel")
        assertThat(cancelPost.body).contains("\"orderLinkId\":\"c1\"")
        assertThat(cancelPost.body).contains("\"category\":\"spot\"")
    }

    @Test
    fun `cancel of unknown order is a no-op`() {
        val client = FakeBybitClient()
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }
        broker.cancel("does-not-exist")
        assertThat(client.posts).isEmpty()
    }

    @Test
    fun `WS order frame with status New publishes OrderAccepted`() {
        val client = FakeBybitClient()
        val bus = newBus()
        val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
        bus.subscribe<BrokerEvent.OrderAccepted> { accepts.add(it) }
        BybitSpotBroker(client, bus, FixedClock(0L))

        val frame =
            Json
                .parseToJsonElement(
                    """{"topic":"order","data":[{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","orderStatus":"New","category":"spot"}]}""",
                ).jsonObject
        client.emitWsFrame("order", frame)

        assertThat(accepts).hasSize(1)
        assertThat(accepts.single().clientOrderId).isEqualTo("c1")
        assertThat(accepts.single().brokerOrderId).isEqualTo("abc-123")
    }

    @Test
    fun `WS order frame with status Cancelled publishes OrderCancelled`() {
        val client = FakeBybitClient()
        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        BybitSpotBroker(client, bus, FixedClock(0L))

        val frame =
            Json
                .parseToJsonElement(
                    """{"topic":"order","data":[{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","orderStatus":"Cancelled","category":"spot"}]}""",
                ).jsonObject
        client.emitWsFrame("order", frame)

        assertThat(cancels).hasSize(1)
        assertThat(cancels.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `WS execution frame publishes OrderFilled with re-prefixed symbol`() {
        val client = FakeBybitClient()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        BybitSpotBroker(client, bus, FixedClock(0L))

        val frame =
            Json
                .parseToJsonElement(
                    """{"topic":"execution","data":[{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","execPrice":"79998.5","execQty":"0.01","execId":"e-1","category":"spot"}]}""",
                ).jsonObject
        client.emitWsFrame("execution", frame)

        assertThat(fills).hasSize(1)
        assertThat(fills.single().clientOrderId).isEqualTo("c1")
        assertThat(fills.single().symbol).isEqualTo("BYBIT_SPOT:BTCUSDT")
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("79998.5"))
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("0.01"))
    }

    @Test
    fun `modify posts to v5 order amend with the changes`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        client.responses["/v5/order/amend"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L)).also { client.posts.clear() }

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

        val ack =
            broker.modify(
                "c1",
                OrderModification(newLimitPrice = Money.of("80500")),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(client.posts.last().path).isEqualTo("/v5/order/amend")
        assertThat(client.posts.last().body).contains("\"price\":\"80500")
        assertThat(client.posts.last().body).contains("\"orderLinkId\":\"c1\"")
    }
}
