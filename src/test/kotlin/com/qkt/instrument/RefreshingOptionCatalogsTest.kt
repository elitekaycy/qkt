package com.qkt.instrument

import com.qkt.common.FixedClock
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RefreshingOptionCatalogsTest {
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
        )
    private val first = OptionListing("BTC_USDC-2OCT26-92000-C", "92000", "call", 1_790_928_000_000L)
    private val listedLater = OptionListing("BTC_USDC-9OCT26-95000-C", "95000", "call", 1_791_532_800_000L)

    @Test
    fun `a contract listed after the start is found once its refreshed catalog is due, not before`(
        @TempDir dir: Path,
    ) {
        val store = OptionCatalogStore(dir)
        store.write(OptionCatalog(root.root, listOf(first)))
        val clock = FixedClock(0L)
        val catalogs = RefreshingOptionCatalogs(listOf(root), dir, clock, recheckMs = 60_000)
        assertThat(catalogs.lookup("DERIBIT:BTC_USDC_9OCT26_95000_C")).isNull()

        store.write(OptionCatalog(root.root, listOf(first, listedLater), mapOf("2026-10-02" to "95000")))
        clock.time = 59_999
        assertThat(catalogs.lookup("DERIBIT:BTC_USDC_9OCT26_95000_C")).isNull()
        clock.time = 60_000

        assertThat(catalogs.lookup("DERIBIT:BTC_USDC_9OCT26_95000_C")).isNotNull()
        assertThat(catalogs.options().listings(root.root).keys).contains(listedLater.symbol)
        assertThat(catalogs.options().deliveryPrice("DERIBIT:BTC_USDC_2OCT26_92000_C")).isEqualByComparingTo("95000")
    }

    @Test
    fun `an unchanged catalog is not reloaded, and a catalog that cannot be read keeps the last good one`(
        @TempDir dir: Path,
    ) {
        val store = OptionCatalogStore(dir)
        store.write(OptionCatalog(root.root, listOf(first)))
        val clock = FixedClock(0L)
        val catalogs = RefreshingOptionCatalogs(listOf(root), dir, clock, recheckMs = 1_000)
        val loaded = catalogs.options()

        clock.time = 5_000
        assertThat(catalogs.options()).isSameAs(loaded)

        Files.writeString(store.path(root.root), "{ not json")
        clock.time = 10_000
        assertThat(catalogs.lookup("DERIBIT:BTC_USDC_2OCT26_92000_C")).isNotNull()
    }
}
