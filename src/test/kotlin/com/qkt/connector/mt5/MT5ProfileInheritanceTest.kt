package com.qkt.connector.mt5

import com.qkt.broker.PositionAccountingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A fresh profile's own defaults, and a child profile inheriting every identity check from its parent. */
class MT5ProfileInheritanceTest {
    private val loader = MT5BrokerProfileLoader()
    private val parent =
        mapOf(
            "type" to "mt5",
            "gateway_url" to "http://h:6000",
            "magic" to "77",
            "server_tz_offset_hours" to "2",
            "api_key" to "k",
            "http_timeout_ms" to "900",
            "retry_attempts" to "1",
            "deviation_points" to "5",
            "expected_account_login" to "4242",
            "expected_account_server" to "Broker-Live",
            "expected_trade_mode" to "real",
            "expected_account_currency" to "eur",
            "expected_leverage" to "30",
            "expected_margin_mode" to " Netting ",
        )

    @Test
    fun `a fresh profile with a fixed offset and no suffix takes the plain defaults`() {
        val raw =
            mapOf(
                "myforex" to
                    mapOf(
                        "type" to "mt5",
                        "gateway_url" to "http://h:6000",
                        "magic" to "77",
                        "server_tz_offset_hours" to "3",
                    ),
            )

        val profile = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()).single()

        assertThat(profile.symbolPolicy).isEqualTo(SymbolPolicy(suffix = "", aliases = emptyMap()))
        assertThat(profile.serverTimeZone).isEqualTo(MT5ServerTimeZone.fixedOffset(3))
        assertThat(listOf(profile.httpTimeoutMs, profile.retryAttempts.toLong(), profile.deviationPoints.toLong()))
            .containsExactly(5_000L, 3L, 20L)
        assertThat(profile.capabilityRestrictions).isEmpty()
        assertThat(profile.instrumentOverrides).isEmpty()
        assertThat(profile.apiKey).isNull()
        assertThat(
            listOf(profile.expectedAccountLogin, profile.expectedAccountServer, profile.expectedTradeMode),
        ).containsOnlyNulls()
        assertThat(
            listOf(profile.expectedAccountCurrency, profile.expectedLeverage, profile.expectedMarginMode),
        ).containsOnlyNulls()
    }

    @Test
    fun `a parent's settings and identity checks are read, netting among them`() {
        val profile = loader.load(mapOf("acct" to parent), MT5DefaultProfiles.all, env = emptyMap()).single()

        assertThat(profile.serverTimeZone).isEqualTo(MT5ServerTimeZone.fixedOffset(2))
        assertThat(profile.expectedMarginMode).isEqualTo(PositionAccountingMode.NETTING)
        assertThat(profile.expectedAccountCurrency).isEqualTo("EUR")
    }

    @Test
    fun `a child profile inherits every setting and identity check it does not set`() {
        val raw =
            mapOf(
                "acct" to parent,
                "acct-two" to mapOf("type" to "mt5", "extends" to "acct", "magic" to "78"),
            )

        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()).associateBy { it.name }
        val child = profiles.getValue("acct-two")

        assertThat(child.magic).isEqualTo(78)
        assertThat(child.gatewayUrl).isEqualTo("http://h:6000")
        assertThat(child.serverTimeZone).isEqualTo(MT5ServerTimeZone.fixedOffset(2))
        assertThat(listOf(child.httpTimeoutMs, child.retryAttempts.toLong(), child.deviationPoints.toLong()))
            .containsExactly(900L, 1L, 5L)
        assertThat(child.apiKey).isEqualTo("k")
        assertThat(child.expectedAccountLogin).isEqualTo(4242L)
        assertThat(child.expectedAccountServer).isEqualTo("Broker-Live")
        assertThat(child.expectedTradeMode).isEqualTo(MT5TradeMode.REAL)
        assertThat(child.expectedAccountCurrency).isEqualTo("EUR")
        assertThat(child.expectedLeverage).isEqualTo(30)
        assertThat(child.expectedMarginMode).isEqualTo(PositionAccountingMode.NETTING)
    }
}
