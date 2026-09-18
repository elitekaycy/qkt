package com.qkt.connector.bybit

import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.TradingAccount
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitConnectorTest {
    private val env = mapOf("BYBIT_API_KEY" to "k", "BYBIT_API_SECRET" to "s")
    private val context = ConnectorContext(stateRoot = null, env = env, clock = SystemClock())

    private fun acct(
        name: String,
        category: String,
        vararg extra: Pair<String, String>,
    ) = AccountConfig(
        name = name,
        type = "bybit",
        settings =
            mapOf(
                "type" to "bybit",
                "category" to category,
                "api_key" to "env:BYBIT_API_KEY",
                "api_secret" to "env:BYBIT_API_SECRET",
            ) + extra,
    )

    private fun recordingConnector(built: MutableList<BybitCredentials>) =
        BybitConnector(
            clientFactory = { creds ->
                built += creds
                BybitClient(creds.apiKey.reveal(), creds.apiSecret.reveal(), creds.testnet)
            },
        )

    @Test
    fun `spot and linear accounts serve their existing prefixes with crypto hours`() {
        val (spot, linear) =
            BybitConnector().open(listOf(acct("bybit_spot", "spot"), acct("bybit_linear", "linear")), context)

        assertThat(spot.marketData?.supports("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(linear.marketData?.supports("BYBIT_LINEAR:BTCUSDT")).isTrue()
        assertThat(spot.tradingHours.calendarFor("BTCUSDT").name).isEqualTo("crypto")
    }

    @Test
    fun `accounts with the same credentials share one client, built only when trading needs it`() {
        val built = mutableListOf<BybitCredentials>()
        val accounts: List<TradingAccount> =
            recordingConnector(built).open(
                listOf(acct("bybit_spot", "spot"), acct("bybit_linear", "linear")),
                context,
            )

        assertThat(built).isEmpty()
        accounts.forEach { (it as BybitTradingAccount).client() }
        assertThat(built).hasSize(1)
        assertThat(built.single().testnet).isTrue()
        assertThat(built.single().apiKey.reveal()).isEqualTo("k")
        accounts.forEach { it.close() }
    }

    @Test
    fun `different credentials get different clients`() {
        val built = mutableListOf<BybitCredentials>()
        val accounts =
            recordingConnector(built).open(
                listOf(acct("bybit_spot", "spot"), acct("bybit_linear", "linear", "testnet" to "false")),
                context,
            )

        accounts.forEach { (it as BybitTradingAccount).client() }

        assertThat(built.map { it.testnet }).containsExactly(true, false)
        accounts.forEach { it.close() }
    }

    @Test
    fun `an account name that does not match its category is refused`() {
        assertThatThrownBy { BybitConnector().open(listOf(acct("my_bybit", "linear")), context) }
            .hasMessageContaining("my_bybit")
            .hasMessageContaining("bybit_linear")
    }

    @Test
    fun `an unknown category is refused`() {
        assertThatThrownBy { BybitConnector().open(listOf(acct("bybit_option", "option")), context) }
            .hasMessageContaining("bybit_option.category")
    }

    @Test
    fun `empty credentials are refused at open, not discovered by a connect timeout`() {
        val blankEnv =
            ConnectorContext(
                stateRoot = null,
                env = mapOf("BYBIT_API_KEY" to "", "BYBIT_API_SECRET" to "s"),
                clock = SystemClock(),
            )

        assertThatThrownBy { BybitConnector().open(listOf(acct("bybit_spot", "spot")), blankEnv) }
            .hasMessageContaining("bybit_spot.api_key")
            .hasMessageContaining("empty")
    }

    @Test
    fun `missing credentials are refused naming the field`() {
        val noKey = AccountConfig("bybit_spot", "bybit", mapOf("category" to "spot"))

        assertThatThrownBy { BybitConnector().open(listOf(noKey), context) }
            .hasMessageContaining("bybit_spot.api_key")
    }
}
