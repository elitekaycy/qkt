package com.qkt.connector.mt5

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5ProfileSettingsTest {
    private val loader = MT5BrokerProfileLoader()

    private fun entry(vararg extra: Pair<String, String>) =
        mapOf("prop" to mapOf("type" to "mt5", "extends" to "exness", "magic" to "11005") + extra)

    @Test
    fun `a misspelled identity check is refused instead of silently disabling the check`() {
        assertThatThrownBy {
            loader.load(entry("expected_acount_login" to "999999999"), MT5DefaultProfiles.all, env = emptyMap())
        }.hasMessageContaining(
            "brokers.prop has unknown setting 'expected_acount_login' (did you mean 'expected_account_login'?)",
        )
    }

    @Test
    fun `every setting the loader reads is accepted, with the shared nested blocks`() {
        val everything =
            mapOf(
                "gateway_url" to "http://gw:5001",
                "api_key" to "k",
                "symbol_suffix" to "m",
                "server_time_zone" to "Etc/UTC",
                "poll_interval_ms" to "1000",
                "tick_poll_interval_ms" to "250",
                "http_timeout_ms" to "5000",
                "retry_attempts" to "2",
                "deviation_points" to "20",
                "expected_account_login" to "1",
                "expected_account_server" to "S",
                "expected_trade_mode" to "demo",
                "expected_account_currency" to "USD",
                "expected_leverage" to "100",
                "expected_margin_mode" to "hedging",
                "calendars" to "{BTC*=crypto}",
                "aliases" to "{}",
                "capability_restrictions" to "[]",
                "instrument_overrides" to "{}",
            )

        val profiles = loader.load(entry(*everything.toList().toTypedArray()), MT5DefaultProfiles.all, env = emptyMap())

        assertThat(profiles.single().expectedAccountLogin).isEqualTo(1L)
        assertThat(MT5ProfileSettings.KEYS).containsAll(
            everything.keys - "calendars" - "aliases" - "capability_restrictions" - "instrument_overrides",
        )
    }

    @Test
    fun `a misspelled instrument override field is refused`() {
        val spec = mapOf("min_volume" to "0.01", "volume_stp" to "0.01")
        assertThatThrownBy {
            loader.load(
                entry(),
                MT5DefaultProfiles.all,
                emptyMap(),
                instrumentOverrides =
                    mapOf(
                        "prop" to mapOf("XAUUSD" to spec),
                    ),
            )
        }.hasMessageContaining("instrument 'XAUUSD' has unknown field(s) [volume_stp]")
    }
}
