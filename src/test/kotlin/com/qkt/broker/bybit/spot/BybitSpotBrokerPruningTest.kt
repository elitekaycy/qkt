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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotBrokerPruningTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `OrderFilled prunes symbolByClientOrderId`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val bus = newBus()
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))

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

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "abc",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                price = Money.of("80000"),
                quantity = Money.of("0.01"),
            ),
        )

        client.posts.clear()
        broker.cancel("c1")
        assertThat(client.posts).isEmpty()
    }

    @Test
    fun `OrderCancelled prunes symbolByClientOrderId`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val bus = newBus()
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))

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
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = "c1",
                brokerOrderId = "abc",
                reason = "user cancel",
            ),
        )

        client.posts.clear()
        broker.cancel("c1")
        assertThat(client.posts).isEmpty()
    }

    @Test
    fun `OrderRejected prunes symbolByClientOrderId`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val bus = newBus()
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))

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
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = "abc",
                reason = "test",
            ),
        )

        client.posts.clear()
        broker.cancel("c1")
        assertThat(client.posts).isEmpty()
    }
}
