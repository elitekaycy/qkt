package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionCatalogRegistryTest {
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )
    private val catalog =
        OptionCatalog(
            "DERIBIT:BTC_USDC",
            listOf(OptionListing("BTC_USDC-27SEP24-60000-C", "60000", "call", 1_727_424_000_000L)),
            mapOf("2024-09-27" to "65422.7"),
        )

    @Test
    fun `a catalogued option resolves and its catalog round-trips through the store`(
        @TempDir dir: Path,
    ) {
        OptionCatalogStore(dir).write(catalog)
        val registry = OptionCatalogRegistry.load(listOf(root), OptionCatalogStore(dir))

        val meta = requireNotNull(registry.lookup("DERIBIT:BTC_USDC_27SEP24_60000_C"))
        assertThat((meta.derivative as OptionTerms).strike).isEqualByComparingTo("60000")
        assertThat(registry.deliveryPrice("DERIBIT:BTC_USDC_27SEP24_60000_C")).isEqualByComparingTo("65422.7")
        assertThat(OptionCatalogStore(dir).read("DERIBIT:BTC_USDC")).isEqualTo(catalog)
    }

    @Test
    fun `an option of a declared root missing from the catalog names the refresh command`(
        @TempDir dir: Path,
    ) {
        val registry = OptionCatalogRegistry.load(listOf(root), OptionCatalogStore(dir))

        assertThat(registry.lookup("DERIBIT:BTC_USDC_27DEC24_90000_P")).isNull()
        assertThat(
            registry.missingReason("DERIBIT:BTC_USDC_27DEC24_90000_P"),
        ).contains("qkt fetch DERIBIT:BTC_USDC --catalog")
        assertThat(registry.missingReason("EXNESS:XAUUSD")).isNull()
    }

    @Test
    fun `only option names of a root are claimed, and only listings that belong to it are loaded`(
        @TempDir dir: Path,
    ) {
        val registry = OptionCatalogRegistry.load(listOf(root), OptionCatalogStore(dir))

        assertThat(registry.missingReason("DERIBIT:BTC_USDC_PERPETUAL")).isNull()
        val stray =
            catalog.copy(
                contracts = listOf(OptionListing("ETH_USDC-27SEP24-3000-C", "3000", "call", 1_727_424_000_000L)),
            )
        org.assertj.core.api.Assertions
            .assertThatThrownBy { OptionCatalogRegistry(listOf(root), mapOf(root.root to stray)) }
            .hasMessageContaining("ETH_USDC-27SEP24-3000-C")
    }
}
