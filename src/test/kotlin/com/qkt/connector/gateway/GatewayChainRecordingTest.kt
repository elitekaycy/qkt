package com.qkt.connector.gateway

import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionChainFixture
import com.qkt.derivatives.options.chain.OptionChainFixture.Companion.ms
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.QuoteSource
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GatewayChainRecordingTest {
    private fun registry(
        dir: Path,
        series: QuoteSource,
    ): OptionCatalogRegistry {
        val f = OptionChainFixture(dir)
        val root = f.root.copy(chains = series)
        val listing = OptionListing(f.venueName, "92000", "call", f.expiryMs)
        return OptionCatalogRegistry(listOf(root), mapOf(root.root to OptionCatalog(root.root, listOf(listing))), dir)
    }

    private fun quote(
        atMs: Long,
        underlying: String? = "83000",
    ) = WireQuote("BTC_USDC-2OCT26-92000-C", "640", "655", "1", "1", "648.5", "52.3", underlying, atMs)

    @Test
    fun `a book root's live quotes become book snapshots in its chain store`(
        @TempDir dir: Path,
    ) {
        val recording = GatewayChainRecording(registry(dir, QuoteSource.BOOK), cadenceMs = 60_000)
        val sink = recording.sinkFor("DERIBIT:BTC_USDC")!!

        sink(quote(ms("2026-10-01T10:00:10Z")))
        sink(quote(ms("2026-10-01T10:00:20Z"), underlying = null))
        sink(quote(ms("2026-10-01T10:01:05Z")))
        recording.close()

        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val deadline = System.currentTimeMillis() + 5_000
        while (store.readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")).isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "no snapshot written" }
            Thread.sleep(10)
        }
        val snapshot = store.readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")).single()
        assertThat(snapshot.atMs).isEqualTo(ms("2026-10-01T10:01:00Z"))
        val written = snapshot.quotes.single()
        assertThat(written.markAgeMs).isEqualTo(50_000L)
        assertThat(written.bid).isEqualByComparingTo("640")
        assertThat(written.markIv).isEqualByComparingTo("52.3")
    }

    @Test
    fun `a root without a book series, or no options at all, records nothing`(
        @TempDir dir: Path,
    ) {
        assertThat(GatewayChainRecording(registry(dir, QuoteSource.TRADE), 60_000).sinkFor("DERIBIT:BTC_USDC")).isNull()
        assertThat(GatewayChainRecording(registry(dir, QuoteSource.BOOK), 60_000).sinkFor("DERIBIT:ETH_USDC")).isNull()
        assertThat(GatewayChainRecording(null, 60_000).sinkFor("DERIBIT:BTC_USDC")).isNull()
    }

    @Test
    fun `quotes after close are ignored and never fail the feed`(
        @TempDir dir: Path,
    ) {
        val recording = GatewayChainRecording(registry(dir, QuoteSource.BOOK), cadenceMs = 60_000)
        val sink = recording.sinkFor("DERIBIT:BTC_USDC")!!
        sink(quote(ms("2026-10-01T10:00:10Z")))
        recording.close()

        sink(quote(ms("2026-10-01T10:01:05Z")))

        assertThat(ChainSnapshotStore(dir, QuoteSource.BOOK).readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")))
            .isEmpty()
    }
}
