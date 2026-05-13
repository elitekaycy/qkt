package com.qkt.broker.bybit.linear

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitLinearBrokerTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun emptyOk() = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun makeBroker(client: FakeBybitClient): BybitLinearBroker {
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()
        client.responses["/v5/position/list"] = emptyOk()
        val broker = BybitLinearBroker(client, newBus(), FixedClock(0L), PositionTracker())
        client.posts.clear()
        return broker
    }

    @Test
    fun `getOpenPositions parses linear position list and emits BYBIT_LINEAR symbol`() {
        val client = FakeBybitClient()
        val broker = makeBroker(client)
        client.responses["/v5/position/list"] =
            """
            {"retCode":0,"retMsg":"OK","result":{"list":[
              {"symbol":"BTCUSDT","side":"Buy","size":"0.10","avgPrice":"60000.5"},
              {"symbol":"ETHUSDT","side":"Sell","size":"1.5","avgPrice":"3200.0"}
            ]}}
            """.trimIndent()

        val positions = broker.getOpenPositions()
        assertThat(positions.keys).containsExactlyInAnyOrder("BYBIT_LINEAR:BTCUSDT", "BYBIT_LINEAR:ETHUSDT")
        val btc = positions["BYBIT_LINEAR:BTCUSDT"]!!
        assertThat(btc.quantity.toPlainString()).isEqualTo("0.10")
        assertThat(btc.avgEntryPrice.toPlainString()).isEqualTo("60000.5")
        val eth = positions["BYBIT_LINEAR:ETHUSDT"]!!
        assertThat(eth.quantity.toPlainString()).isEqualTo("-1.5") // Sell flips sign
    }

    @Test
    fun `getOpenPositions returns empty on non-zero retCode`() {
        val client = FakeBybitClient()
        val broker = makeBroker(client)
        client.responses["/v5/position/list"] = """{"retCode":10001,"retMsg":"err","result":{"list":[]}}"""
        assertThat(broker.getOpenPositions()).isEmpty()
    }

    @Test
    fun `getOpenPositions skips entries with zero size`() {
        val client = FakeBybitClient()
        val broker = makeBroker(client)
        client.responses["/v5/position/list"] =
            """
            {"retCode":0,"retMsg":"OK","result":{"list":[
              {"symbol":"BTCUSDT","side":"Buy","size":"0","avgPrice":"60000"},
              {"symbol":"ETHUSDT","side":"Buy","size":"0.5","avgPrice":"3200"}
            ]}}
            """.trimIndent()
        val positions = broker.getOpenPositions()
        assertThat(positions.keys).containsExactly("BYBIT_LINEAR:ETHUSDT")
    }

    @Test
    fun `name capabilities and supports`() {
        val broker = makeBroker(FakeBybitClient())
        assertThat(broker.name).isEqualTo("BybitLinear")
        assertThat(broker.supports("BYBIT_LINEAR:BTCUSDT")).isTrue
        assertThat(broker.supports("BYBIT_SPOT:BTCUSDT")).isFalse
        assertThat(broker.supports("OANDA:EURUSD")).isFalse
    }

    @Test
    fun `submit Market posts to v5 order create with category linear and positionIdx`() {
        val client = FakeBybitClient()
        val broker = makeBroker(client)
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c1",
                    symbol = "BYBIT_LINEAR:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(client.posts.single().path).isEqualTo("/v5/order/create")
        assertThat(client.posts.single().body).contains("\"category\":\"linear\"")
        assertThat(client.posts.single().body).contains("\"positionIdx\":0")
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isEqualTo("abc")
    }

    @Test
    fun `submit rejects symbol that does not start with BYBIT_LINEAR`() {
        val broker = makeBroker(FakeBybitClient())

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

        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("does not support")
    }

    @Test
    fun `init triggers reconcile with all four REST paths including position-list`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()
        client.responses["/v5/position/list"] = emptyOk()

        BybitLinearBroker(client, newBus(), FixedClock(0L), PositionTracker())

        val paths = client.posts.map { it.path }
        assertThat(paths).contains(
            "/v5/order/realtime",
            "/v5/execution/list",
            "/v5/account/wallet-balance",
            "/v5/position/list",
        )
    }
}
