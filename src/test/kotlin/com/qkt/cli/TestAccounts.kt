package com.qkt.cli

import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry

/** Builds account directories through the real, discovered connectors for wiring tests. */
internal object TestAccounts {
    private val env = mapOf("BYBIT_TRADER_TOKEN" to "k")

    fun directory(vararg accounts: AccountConfig): AccountDirectory =
        AccountDirectory.open(
            accounts.toList(),
            ConnectorRegistry.discover(),
            ConnectorContext(stateRoot = null, env = env, clock = SystemClock()),
        )

    /** An MT5 account inheriting the Exness defaults. */
    fun mt5(
        name: String,
        magic: Int = 1,
        gatewayUrl: String = "http://gateway:8080",
        tradingHours: List<Pair<String, String>> = emptyList(),
    ): AccountConfig =
        AccountConfig(
            name = name,
            type = "mt5",
            settings =
                mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to gatewayUrl, "magic" to "$magic"),
            tradingHours = tradingHours,
        )

    /**
     * A Bybit account for [category]: a `type: gateway` entry named `bybit_<category>` on the gateway running the
     * Bybit adapter for that category, its token read from the test environment. Nothing connects until it trades.
     */
    fun bybit(category: String): AccountConfig =
        AccountConfig(
            name = "bybit_$category",
            type = "gateway",
            settings =
                mapOf(
                    "type" to "gateway",
                    "gateway_url" to "http://gateway-bybit-$category:8443",
                    "api_key" to "env:BYBIT_TRADER_TOKEN",
                    "expected_adapter" to "bybit",
                    "expected_account_login" to "1",
                    "expected_trade_mode" to "demo",
                ),
        )
}
