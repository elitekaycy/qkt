package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.events.BrokerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The VGP v1 names qkt reads off the wire: cost kinds, stream event types and gateway order ids. */
class GatewayWireNamesTest {
    @Test
    fun `every VGP cost kind maps to its qkt cost, and an unknown one is a protocol error`() {
        assertThat(listOf("commission", "exchange_fee", "delivery_fee", "funding", "swap").map(WireValues::kindOf))
            .containsExactly(
                CostKind.COMMISSION,
                CostKind.EXCHANGE_FEE,
                CostKind.EXCHANGE_FEE,
                CostKind.FUNDING,
                CostKind.SWAP,
            )
        assertThatThrownBy { WireValues.kindOf("rebate") }
            .isInstanceOf(GatewayProtocolException::class.java)
            .hasMessageContaining("cost kind 'rebate'")
    }

    @Test
    fun `an account event reaches the account handler and a kill event reaches none`() {
        val accounts = mutableListOf<WireAccount>()
        val elsewhere = mutableListOf<Any>()
        val decoder =
            GatewayEventDecoder(
                onOrder = { elsewhere.add(it) },
                onFill = { elsewhere.add(it) },
                onSettlement = { elsewhere.add(it) },
                onPosition = { elsewhere.add(it) },
                onAccount = { accounts.add(it) },
                onFunding = { elsewhere.add(it) },
            )
        val account =
            Json.parseToJsonElement(
                """{"currency":"USDC","balance":"100","equity":"101","margin_used":"5","margin_available":"96"}""",
            )

        decoder.decode(WireEvent("s1", 1, "account", data = account))
        decoder.decode(WireEvent("s1", 2, "kill", data = JsonPrimitive("all")))

        assertThat(accounts.single().equity).isEqualTo("101")
        assertThat(elsewhere).isEmpty()
    }

    @Test
    fun `a cancel failure and a modify carry the engine's order id back from the gateway's`() {
        val gatewayId = GatewayClientIds.remember("dsl-soak_o13_bybit_fund--0-exit-close-r945") + ".k2"

        val failed = GatewayClientIds.toEngine(BrokerEvent.OrderCancelFailed(gatewayId, "v-1", "too late"))
        val modified = GatewayClientIds.toEngine(BrokerEvent.OrderModified(gatewayId, "v-1"))

        assertThat(failed.clientOrderId).isEqualTo("dsl-soak_o13_bybit_fund--0-exit-close-r945")
        assertThat((failed as BrokerEvent.OrderCancelFailed).reason).isEqualTo("too late")
        assertThat(modified.clientOrderId).isEqualTo("dsl-soak_o13_bybit_fund--0-exit-close-r945")
    }
}
