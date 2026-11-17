package com.qkt.marketdata.hub

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The arms of `hub:` config parsing the main test does not reach: defaults, blanks and refusals. */
class HubStoreConfigParseTest {
    @Test
    fun `a block that is not a map means no store`() {
        assertThat(HubStoreConfig.parse("hub")).isEqualTo(HubStoreConfig.NONE)
        assertThat(HubStoreConfig.parse(listOf("root"))).isEqualTo(HubStoreConfig.NONE)
    }

    @Test
    fun `an empty block takes every default`() {
        val cfg = HubStoreConfig.parse(emptyMap<String, Any?>())

        assertThat(cfg.root).isNull()
        assertThat(cfg.policy.minLagMs).isZero()
        assertThat(cfg.policy.refuseDerived).isFalse()
        assertThat(cfg.staleAfterMs).isEqualTo(HubStoreConfig.DEFAULT_STALE_AFTER_MS)
    }

    @Test
    fun `a blank or null root is no root and a padded one is trimmed`() {
        assertThat(HubStoreConfig.parse(mapOf("root" to "   ")).root).isNull()
        assertThat(HubStoreConfig.parse(mapOf("root" to null)).root).isNull()
        assertThat(HubStoreConfig.parse(mapOf("root" to "  /hub  ")).root).isEqualTo(Path.of("/hub"))
    }

    @Test
    fun `numbers and booleans are read from strings and any case`() {
        val cfg =
            HubStoreConfig.parse(
                mapOf("min_lag_ms" to " 5 ", "refuse_derived" to "TRUE", "stale_after_ms" to "10"),
            )

        assertThat(cfg.policy.minLagMs).isEqualTo(5L)
        assertThat(cfg.policy.refuseDerived).isTrue()
        assertThat(cfg.staleAfterMs).isEqualTo(10L)
        assertThat(HubStoreConfig.parse(mapOf("refuse_derived" to "False")).policy.refuseDerived).isFalse()
    }

    @Test
    fun `unknown keys are listed sorted`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("zeta" to 1, "alpha" to 2)) }
            .hasMessage("unknown hub key(s): alpha, zeta")
    }

    @Test
    fun `a non-integer number is refused naming the key and value`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("min_lag_ms" to "soon")) }
            .hasMessage("hub.min_lag_ms must be an integer, got 'soon'")
        assertThatThrownBy { HubStoreConfig.parse(mapOf("stale_after_ms" to 1.5)) }
            .hasMessage("hub.stale_after_ms must be an integer, got '1.5'")
    }

    @Test
    fun `a non-boolean flag is refused naming the key and value`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("refuse_derived" to 1)) }
            .hasMessage("hub.refuse_derived must be true or false, got '1'")
    }

    @Test
    fun `a stale window must be positive`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("stale_after_ms" to 0)) }
            .hasMessage("hub.stale_after_ms must be positive, got 0")
        assertThatThrownBy { HubStoreConfig(root = null, staleAfterMs = -1L) }
            .hasMessageContaining("must be positive")
    }

    @Test
    fun `the cli root wins over config and a padded cli root is trimmed`() {
        val cfg = HubStoreConfig(root = Path.of("/cfg"))

        assertThat(resolveHubRoot(" /cli ", cfg, Path.of("/data"))).isEqualTo(Path.of("/cli"))
        assertThat(resolveHubRoot("", cfg, Path.of("/data"))).isEqualTo(Path.of("/cfg"))
    }
}
