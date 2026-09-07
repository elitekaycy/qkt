package com.qkt.marketdata.hub

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The `hub:` block and where a run's store root comes from.
 *
 * Two properties matter. Unknown keys fail at load, because this block carries a safety setting
 * (`refuse_derived`) and a typo must not silently switch it off. And a live process never falls
 * back to a local default directory: a book that binds a hub stream with no store configured must
 * fail at deploy, not read an empty directory and trade as if every fact were unknown.
 */
class HubStoreConfigTest {
    @Test
    fun `absent block means no store`() {
        assertThat(HubStoreConfig.parse(null)).isEqualTo(HubStoreConfig.NONE)
        assertThat(HubStoreConfig.NONE.root).isNull()
    }

    @Test
    fun `every key parses`() {
        val cfg =
            HubStoreConfig.parse(
                mapOf(
                    "root" to "/var/lib/qkt-hub",
                    "min_lag_ms" to 60000,
                    "refuse_derived" to true,
                    "stale_after_ms" to 120000,
                ),
            )
        assertThat(cfg.root).isEqualTo(Path.of("/var/lib/qkt-hub"))
        assertThat(cfg.policy.minLagMs).isEqualTo(60_000L)
        assertThat(cfg.policy.refuseDerived).isTrue()
        assertThat(cfg.staleAfterMs).isEqualTo(120_000L)
    }

    @Test
    fun `an unknown key is rejected rather than ignored`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("root" to "/x", "refuse_derved" to true)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refuse_derved")
    }

    @Test
    fun `a non-boolean refuse_derived is rejected`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("refuse_derived" to "yes")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `backtest precedence is cli then config then default`() {
        val data = Path.of("/data")
        val cfg = HubStoreConfig(root = Path.of("/cfg"))
        assertThat(resolveHubRoot("/cli", cfg, data)).isEqualTo(Path.of("/cli"))
        assertThat(resolveHubRoot(null, cfg, data)).isEqualTo(Path.of("/cfg"))
        assertThat(resolveHubRoot("  ", HubStoreConfig.NONE, data)).isEqualTo(hubRoot(data))
    }

    @Test
    fun `a live process has no local default`() {
        assertThat(liveHubRoot(HubStoreConfig(root = Path.of("/cfg")))).isEqualTo(Path.of("/cfg"))
        if (System.getenv(HubMarketSource.ROOT_ENV).isNullOrBlank()) {
            assertThat(liveHubRoot(HubStoreConfig.NONE)).isNull()
        }
    }
}
