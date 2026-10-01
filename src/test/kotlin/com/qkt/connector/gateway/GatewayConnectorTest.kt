package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayConnectorTest {
    private val fake = FakeGateway(listOf("BTC_USDC-25DEC26-92000-C"))
    private val context = ConnectorContext(null, mapOf("GW_KEY" to "secret"), FixedClock(5L))

    @AfterEach
    fun stop() = fake.shutdown()

    private fun account(vararg overrides: Pair<String, String>) =
        AccountConfig(
            "deribit_main",
            "gateway",
            mapOf(
                "type" to "gateway",
                "gateway_url" to fake.url,
                "api_key" to "env:GW_KEY",
                "expected_adapter" to "fake",
                "expected_account_login" to "7",
                "expected_trade_mode" to "demo",
            ) + overrides,
        )

    private fun open(config: AccountConfig) = GatewayConnector().open(listOf(config), context).single()

    @Test
    fun `the connector is found by its type and verifies a gateway reporting the expected identity`() {
        assertThat(ConnectorRegistry.discover().find("gateway")).isInstanceOf(GatewayConnector::class.java)

        val profile = open(account()).verify()

        assertThat(profile.accountId).isEqualTo("7")
        assertThat(profile.type).isEqualTo(AccountType.DEMO)
        assertThat(profile.currency).isEqualTo("USDC")
    }

    @Test
    fun `a gateway reporting another adapter, account or trade mode never trades`() {
        assertThatThrownBy {
            open(
                account("expected_adapter" to "deribit"),
            ).verify()
        }.hasMessageContaining("adapter 'fake'")
        assertThatThrownBy {
            open(
                account("expected_account_login" to "8"),
            ).verify()
        }.hasMessageContaining("account '7'")
        assertThatThrownBy {
            open(
                account("expected_trade_mode" to "real"),
            ).verify()
        }.hasMessageContaining("trade mode 'demo'")
    }

    @Test
    fun `every identity setting is required and the trade mode is demo or real`() {
        assertThatThrownBy { open(AccountConfig("x", "gateway", mapOf("gateway_url" to fake.url))) }
            .hasMessageMatching("brokers.x.expected_[a-z_]+ is required")
        assertThatThrownBy { open(account("expected_trade_mode" to "paper")) }.hasMessageContaining("demo or real")
    }

    @Test
    fun `the account's prices are routed for its contracts and its option roots only`() {
        val routes = AccountDirectory.open(listOf(account()), ConnectorRegistry.discover(), context).marketDataRoutes()

        val (pattern, source) = routes.single()
        assertThat(source).isInstanceOf(GatewayMarketSource::class.java)
        assertThat(pattern.matches("DERIBIT_MAIN:BTC_USDC_25DEC26_92000_C")).isTrue()
        assertThat(pattern.matches("OPTIONS:DERIBIT_MAIN.BTC_USDC")).isTrue()
        assertThat(pattern.matches("OPTIONS:DERIBIT.BTC_USDC")).isFalse()
        assertThat(pattern.matches("CHAIN:DERIBIT_MAIN.BTC_USDC.IV_ATM_30D")).isFalse()
    }
}
