package com.qkt.cli

import com.qkt.common.FixedClock
import com.qkt.instrument.EventCatalogStore
import com.qkt.instrument.EventFamilyFixture
import com.qkt.instrument.EventTerms
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DerivativeLayersTest {
    @TempDir
    lateinit var dir: Path

    private fun instruments(): Path =
        dir.resolve("instruments.yaml").also {
            Files.writeString(
                it,
                """
                events:
                  - { root: KALSHI:KXT, currency: USD, tickSize: 0.01, volumeStep: 1, volumeMin: 1 }
                options:
                  - { root: DERIBIT:BTC_USDC, currency: USDC, contractSize: 0.01, tickSize: 5, volumeStep: 0.01, volumeMin: 0.01, underlyingIndex: btc_usdc }
                futures:
                  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, volumeStep: 0.001, volumeMin: 0.001 }
                """.trimIndent(),
            )
        }

    @Test
    fun `an events section adds the family's catalogued contracts to the registry`() {
        instruments()
        EventCatalogStore(dir).write(EventFamilyFixture.catalog(mapOf("KXT-E1-T1" to "1")))
        val registry = InstrumentFiles.registry(dir, explicit = null)

        val terms = registry.lookup("KALSHI:KXT_E1_T1")?.derivative as EventTerms
        assertThat(terms.outcome).isEqualByComparingTo("1")
        assertThat(registry.events()?.contracts(EventFamilyFixture.ROOT)).hasSize(6)
        assertThat(registry.futures()).isNotNull
        assertThat(registry.options()).isNotNull
    }

    @Test
    fun `each declared section adds one layer, live option catalogs reload`() {
        val layers = DerivativeLayers.of(instruments(), dir, FixedClock(0L), funding = false)
        assertThat(layers).hasSize(3)
        assertThat(layers.map { it.javaClass.simpleName })
            .containsExactly("ContractCatalogRegistry", "RefreshingOptionCatalogs", "EventCatalogRegistry")
        val none = dir.resolve("none.yaml").also { Files.writeString(it, "instruments: []\n") }
        assertThat(DerivativeLayers.of(none, dir, null, funding = true)).isEmpty()
    }

    @Test
    fun `a contract of a declared family missing from its catalog fails setup naming the import`() {
        instruments()
        assertThatThrownBy { InstrumentFiles.registry(dir, explicit = null, symbols = listOf("KALSHI:KXT_E1_T1")) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("KXT.events.json")
    }
}
