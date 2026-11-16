package com.qkt.instrument

import com.qkt.common.FixedClock
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Without an explicit interval the catalogs recheck every minute. */
class RefreshingOptionCatalogsDefaultsTest {
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
    private val later = OptionListing("BTC_USDC-9OCT26-95000-C", "95000", "call", 1_791_532_800_000L)

    @Test
    fun `the default recheck interval is one minute`(
        @TempDir dir: Path,
    ) {
        Files.createDirectories(dir)
        val store = OptionCatalogStore(dir)
        store.write(OptionCatalog(root.root, listOf(first)))
        val clock = FixedClock(0L)
        val catalogs = RefreshingOptionCatalogs(listOf(root), dir, clock)
        store.write(OptionCatalog(root.root, listOf(first, later), mapOf("2026-10-02" to "95000")))

        clock.time = 59_999
        assertThat(catalogs.lookup("DERIBIT:BTC_USDC_9OCT26_95000_C")).isNull()
        clock.time = 60_000
        assertThat(catalogs.lookup("DERIBIT:BTC_USDC_9OCT26_95000_C")).isNotNull()
    }
}
