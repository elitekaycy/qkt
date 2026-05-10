package com.qkt.broker.mt5

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
        assertThat(ex.serverTzOffsetHours).isEqualTo(2)
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
}
