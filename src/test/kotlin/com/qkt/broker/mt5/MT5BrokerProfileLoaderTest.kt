package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5BrokerProfileLoaderTest {
    private val loader = MT5BrokerProfileLoader()

    @Test
    fun `name match overrides only specified field of default`() {
        val raw =
            mapOf(
                "exness" to mapOf("type" to "mt5", "gateway_url" to "http://h:5005"),
            )
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap())
        val ex = profiles.first { it.name == "exness" }
        assertThat(ex.gatewayUrl).isEqualTo("http://h:5005")
        assertThat(ex.symbolPolicy.suffix).isEqualTo("m")
        assertThat(ex.serverTimeZone).isEqualTo(MT5ServerTimeZone.UTC)
        assertThat(ex.magic).isEqualTo(10001)
    }

    @Test
    fun `extends builds new profile from named base`() {
        val raw =
            mapOf(
                "exness-personal" to
                    mapOf(
                        "type" to "mt5",
                        "extends" to "exness",
                        "gateway_url" to "http://h:5006",
                        "magic" to "10005",
                    ),
            )
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap())
        val p = profiles.first { it.name == "exness-personal" }
        assertThat(p.symbolPolicy.suffix).isEqualTo("m")
        assertThat(p.gatewayUrl).isEqualTo("http://h:5006")
        assertThat(p.magic).isEqualTo(10005)
    }

    @Test
    fun `fresh profile requires required fields`() {
        val raw = mapOf("myforex" to mapOf("type" to "mt5", "gateway_url" to "http://h:6000"))
        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("magic")
    }

    @Test
    fun `env override replaces field`() {
        val raw = mapOf("exness" to mapOf("type" to "mt5"))
        val env = mapOf("QKT_BROKER_EXNESS_GATEWAY_URL" to "http://prod:7000")
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = env)
        assertThat(profiles.first { it.name == "exness" }.gatewayUrl).isEqualTo("http://prod:7000")
    }

    @Test
    fun `fresh profile accepts a DST-aware server time zone`() {
        val raw =
            mapOf(
                "custom" to
                    mapOf(
                        "type" to "mt5",
                        "gateway_url" to "http://h:6000",
                        "magic" to "10006",
                        "server_time_zone" to "Europe/Helsinki",
                    ),
            )

        val profile = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()).single()

        assertThat(profile.serverTimeZone.id).isEqualTo("Europe/Helsinki")
    }

    @Test
    fun `profile rejects competing static and DST-aware time settings`() {
        val raw =
            mapOf(
                "exness" to
                    mapOf(
                        "type" to "mt5",
                        "server_time_zone" to "new_york_close",
                        "server_tz_offset_hours" to "2",
                    ),
            )

        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .hasMessageContaining("must not set both")
    }

    @Test
    fun `api key loads from the profile environment override`() {
        val raw = mapOf("exness" to mapOf("type" to "mt5"))
        val env = mapOf("QKT_BROKER_EXNESS_API_KEY" to "secret-token")
        val profile = loader.load(raw, MT5DefaultProfiles.all, env).single()
        assertThat(profile.apiKey).isEqualTo("secret-token")
    }

    @Test
    fun `expected account identity loads from profile config`() {
        val profile =
            loader
                .load(
                    raw =
                        mapOf(
                            "exness" to
                                mapOf(
                                    "type" to "mt5",
                                    "expected_account_login" to "435898347",
                                    "expected_account_server" to "Exness-MT5Trial9",
                                    "expected_trade_mode" to "demo",
                                    "expected_account_currency" to "usd",
                                    "expected_leverage" to "100",
                                ),
                        ),
                    defaults = MT5DefaultProfiles.all,
                    env = emptyMap(),
                ).single()

        assertThat(profile.expectedAccountLogin).isEqualTo(435898347L)
        assertThat(profile.expectedAccountServer).isEqualTo("Exness-MT5Trial9")
        assertThat(profile.expectedTradeMode).isEqualTo(MT5TradeMode.DEMO)
        assertThat(profile.expectedAccountCurrency).isEqualTo("USD")
        assertThat(profile.expectedLeverage).isEqualTo(100)
    }

    @Test
    fun `non-mt5 type is filtered out`() {
        val raw =
            mapOf(
                "bybit" to mapOf("api_key" to "k"),
                "exness" to mapOf("type" to "mt5"),
            )
        val profiles = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap())
        assertThat(profiles.map { it.name }).containsExactly("exness")
    }

    @Test
    fun `duplicate magic is rejected`() {
        val raw =
            mapOf(
                "exness-a" to
                    mapOf(
                        "type" to "mt5",
                        "extends" to "exness",
                        "gateway_url" to "http://a",
                        "magic" to "777",
                    ),
                "exness-b" to
                    mapOf(
                        "type" to "mt5",
                        "extends" to "exness",
                        "gateway_url" to "http://b",
                        "magic" to "777",
                    ),
            )
        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .hasMessageContaining("magic")
    }

    private fun extendingExness(
        name: String,
        magic: String,
    ) = mapOf(name to mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to "http://h", "magic" to magic))

    @Test
    fun `calendars map builds a per-symbol resolver`() {
        val profiles =
            loader.load(
                extendingExness("x", "11000"),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                calendars = mapOf("x" to listOf("BTC*" to "crypto", "*" to "fx")),
            )
        val p = profiles.first { it.name == "x" }
        assertThat(p.symbolCalendars.calendarFor("BTCUSD").name).isEqualTo("crypto")
        assertThat(p.symbolCalendars.calendarFor("EURUSD").name).isEqualTo("fx")
    }

    @Test
    fun `unknown calendar name is rejected`() {
        assertThatThrownBy {
            loader.load(
                extendingExness("x", "11001"),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                calendars = mapOf("x" to listOf("*" to "stonks")),
            )
        }.hasMessageContaining("unknown calendar")
    }

    @Test
    fun `aliases from yaml merge over extends base`() {
        val profiles =
            loader.load(
                extendingExness("x", "11002"),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                aliases = mapOf("x" to mapOf("BTCUSD" to "BTCUSD")),
            )
        val p = profiles.first { it.name == "x" }
        assertThat(p.symbolPolicy.aliases["BTCUSD"]).isEqualTo("BTCUSD")
        assertThat(p.symbolPolicy.aliases).containsKey("NAS100")
    }

    @Test
    fun `capability restrictions are parsed and subtracted`() {
        val profiles =
            loader.load(
                extendingExness("x", "11003"),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                capabilityRestrictions = mapOf("x" to listOf("TRAILING_STOP")),
            )
        assertThat(profiles.first { it.name == "x" }.capabilities)
            .doesNotContain(OrderTypeCapability.TRAILING_STOP)
    }

    @Test
    fun `unknown capability name is rejected`() {
        assertThatThrownBy {
            loader.load(
                extendingExness("x", "11004"),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                capabilityRestrictions = mapOf("x" to listOf("NONSENSE")),
            )
        }.hasMessageContaining("unknown capability")
    }

    @Test
    fun `instrument overrides are parsed into specs`() {
        val profiles =
            loader.load(
                extendingExness("x", "11005"),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                instrumentOverrides =
                    mapOf(
                        "x" to
                            mapOf(
                                "XAUUSD" to
                                    mapOf(
                                        "min_volume" to "0.01",
                                        "volume_step" to "0.01",
                                        "point_size" to "0.01",
                                        "digits" to "2",
                                        "trade_stops_level_points" to "50",
                                    ),
                            ),
                    ),
            )
        val spec = profiles.first { it.name == "x" }.instrumentOverrides["XAUUSD"]!!
        assertThat(spec.digits).isEqualTo(2)
        assertThat(spec.tradeStopsLevelPoints).isEqualTo(50)
        assertThat(spec.minVolume).isEqualByComparingTo("0.01")
    }

    @Test
    fun `profile with no calendars block defaults to all-fx`() {
        val raw = mapOf("exness" to mapOf("type" to "mt5", "gateway_url" to "http://h"))
        val p = loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()).first { it.name == "exness" }
        assertThat(p.symbolCalendars.calendarFor("EURUSD").name).isEqualTo("fx")
        assertThat(p.symbolCalendars.calendarFor("BTCUSD").name).isEqualTo("fx")
    }
}
