package com.qkt.connector.mt5

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Profiles that cannot be built say which field or parent is missing. */
class MT5ProfileLoaderErrorsTest {
    private val loader = MT5BrokerProfileLoader()

    @Test
    fun `a profile extending a parent nobody defines cannot be resolved`() {
        val raw = mapOf("orphan" to mapOf("type" to "mt5", "extends" to "nowhere", "magic" to "77"))

        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unresolvable extends chain: [orphan]")
    }

    @Test
    fun `a fresh profile with no gateway url is refused`() {
        val raw = mapOf("myforex" to mapOf("type" to "mt5", "magic" to "77"))

        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .hasMessage("MT5 profile 'myforex' missing required field: gateway_url")
    }

    @Test
    fun `a fresh profile with no server time zone is refused`() {
        val raw = mapOf("myforex" to mapOf("type" to "mt5", "magic" to "77", "gateway_url" to "http://h:6000"))

        assertThatThrownBy { loader.load(raw, MT5DefaultProfiles.all, env = emptyMap()) }
            .hasMessage("MT5 profile 'myforex' missing required field: server_time_zone")
    }

    @Test
    fun `MT5 deal entry codes read as names, and an unknown code as its number`() {
        assertThat((0..4).map(::mt5DealEntryName)).containsExactly("IN", "OUT", "INOUT", "OUT_BY", "4")
    }
}
