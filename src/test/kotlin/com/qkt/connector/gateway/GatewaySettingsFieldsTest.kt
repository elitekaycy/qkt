package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A gateway entry's settings: defaults, explicit values, blank and missing required fields. */
class GatewaySettingsFieldsTest {
    private val context = ConnectorContext(null, mapOf("GW_KEY" to "secret", "BLANK_KEY" to " "), FixedClock(5L))
    private val base =
        mapOf(
            "type" to "gateway",
            "gateway_url" to "http://gw:8443/",
            "api_key" to "env:GW_KEY",
            "expected_adapter" to "deribit",
            "expected_account_login" to "7",
            "expected_trade_mode" to "real",
        )

    private fun settings(fields: Map<String, String>) =
        GatewaySettings.of(AccountConfig("main", "gateway", fields), context)

    @Test
    fun `an entry with only its required fields takes every default`() {
        assertThat(settings(base)).isEqualTo(
            GatewaySettings(
                url = "http://gw:8443",
                apiKey = "secret",
                adapter = "deribit",
                accountLogin = "7",
                tradeMode = "real",
                httpTimeoutMs = 5_000L,
                retryAttempts = 3,
                chainSnapshotMs = 300_000L,
                sharedAccount = false,
            ),
        )
    }

    @Test
    fun `explicit timeouts retries chain cadence and sharing are taken as given`() {
        val tuned =
            settings(
                base +
                    mapOf(
                        "http_timeout_ms" to "900",
                        "retry_attempts" to "1",
                        "chain_snapshot_seconds" to "5",
                        "shared_account" to "false",
                    ),
            )

        assertThat(listOf(tuned.httpTimeoutMs, tuned.retryAttempts.toLong(), tuned.chainSnapshotMs))
            .containsExactly(900L, 1L, 5_000L)
        assertThat(tuned.sharedAccount).isFalse()
        assertThat(settings(base + ("chain_snapshot_seconds" to "soon")).chainSnapshotMs)
            .`as`("an unreadable cadence falls back to the default")
            .isEqualTo(300_000L)
    }

    @Test
    fun `a blank required field is as missing as an absent one`() {
        assertThatThrownBy { settings(base + ("expected_adapter" to " ")) }
            .hasMessage("brokers.main.expected_adapter is required")
        assertThatThrownBy { settings(base - "gateway_url") }.hasMessage("brokers.main.gateway_url is required")
    }

    @Test
    fun `a missing or blank api key is refused`() {
        assertThatThrownBy { settings(base - "api_key") }.hasMessage("brokers.main.api_key is required")
        assertThatThrownBy {
            settings(
                base + ("api_key" to "env:BLANK_KEY"),
            )
        }.hasMessage("brokers.main.api_key is required")
    }
}
