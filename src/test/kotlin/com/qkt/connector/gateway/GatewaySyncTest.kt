package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import com.qkt.connector.mt5.Mt5RoutedGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A resynchronization against a hedging account, and against one whose accounting qkt cannot read. */
class GatewaySyncTest {
    private val gateway = Mt5RoutedGateway()
    private val fills = mutableListOf<WireFill>()
    private val sync =
        GatewaySync(
            GatewayClient(gateway.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1),
            onOrder = {},
            onFill = { fills.add(it) },
            onSettlement = {},
            readFunding = {},
            isOwned = { it.startsWith("mine") },
            fromMs = 1_000L,
            now = { 9_000L },
        )

    init {
        gateway.on(
            "/v1/account",
            """{"currency":"USDC","balance":"100","equity":"104","margin_used":"0","margin_available":"104"}""",
        )
        gateway.on("/v1/orders", """{"orders":[]}""")
        gateway.on("/v1/settlements", """{"settlements":[]}""")
        gateway.on(
            "/v1/deals",
            """{"deals":[${fill("mine-1", "f1", 5_000L)},${fill("theirs-1", "f2", 7_000L)}]}""",
        )
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun fill(
        id: String,
        fillId: String,
        time: Long,
    ) = """{"client_order_id":"$id","fill_id":"$fillId","symbol":"BTC-PERP","side":"buy","quantity":"1",""" +
        """"price":"100","time":$time}"""

    @Test
    fun `a hedging account keeps each ticket, and the deal window moves to the newest deal of our own orders`() {
        gateway.on(
            "/v1/positions",
            """{"accounting":"hedging","positions":[{"symbol":"BTC-PERP","quantity":"1","avg_price":"100",""" +
                """"ticket":"t-1"},{"symbol":"BTC-PERP","quantity":"-2","avg_price":"101","ticket":"t-2"}]}""",
        )

        val first = sync.run(emptySet())
        sync.run(emptySet())

        assertThat(first.accounting).isEqualTo(PositionAccountingMode.HEDGING)
        assertThat(first.equity).isEqualByComparingTo("104")
        assertThat(first.positions.getValue("BTC-PERP")).isEqualByComparingTo("-1")
        assertThat(first.tickets.getValue("BTC-PERP").keys).containsExactly("t-1", "t-2")
        val windows = gateway.calls("/v1/deals")
        assertThat(windows[0]).contains("from=1000&to=9000")
        assertThat(windows[1]).contains("from=5000&to=9000")
        assertThat(fills.map { it.fillId }).containsExactly("f1", "f2", "f1", "f2")
    }

    @Test
    fun `an accounting mode qkt does not know stops the resync`() {
        gateway.on("/v1/positions", """{"accounting":"portfolio","positions":[]}""")

        assertThatThrownBy { sync.run(emptySet()) }
            .isInstanceOf(GatewayProtocolException::class.java)
            .hasMessageContaining("position accounting 'portfolio'")
    }
}
