package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionCatalogRegistryEdgesTest {
    @Test
    fun `symbols outside the catalog have no listings, venue name, missing reason or delivery price`(
        @TempDir dir: Path,
    ) {
        val registry = OptionChainFixture(dir).registry

        assertThat(registry.listings("DERIBIT:ETH_USDC")).isEmpty()
        assertThat(registry.venueName("EXNESS:XAUUSD")).isNull()
        assertThat(registry.missingReason("EXNESS:XAUUSD")).isNull()
        assertThat(registry.deliveryPrice("EXNESS:XAUUSD")).isNull()
        assertThat(registry.isOptionCode("EXNESS:XAUUSD")).isFalse()
    }

    @Test
    fun `a catalogued contract is not missing and reads its delivery price only once settled`(
        @TempDir dir: Path,
    ) {
        val settled = OptionChainFixture(dir)
        val unsettled = OptionChainFixture(dir, deliveryPrice = null)

        assertThat(settled.registry.missingReason(settled.symbol)).isNull()
        assertThat(settled.registry.venueName(settled.symbol)).isEqualTo(settled.venueName)
        assertThat(settled.registry.deliveryPrice(settled.symbol)).isEqualByComparingTo("95000")
        assertThat(unsettled.registry.deliveryPrice(unsettled.symbol)).isNull()
    }

    @Test
    fun `a listing of another root in a catalog is refused`(
        @TempDir dir: Path,
    ) {
        val root = OptionChainFixture(dir).root
        val foreign = OptionListing("ETH_USDC-2OCT26-3000-C", "3000", "call", 1L)

        assertThatThrownBy {
            OptionCatalogRegistry(listOf(root), mapOf(root.root to OptionCatalog(root.root, listOf(foreign))), dir)
        }.hasMessageContaining("option ETH_USDC-2OCT26-3000-C in the catalog of DERIBIT:BTC_USDC does not belong to it")
    }
}
