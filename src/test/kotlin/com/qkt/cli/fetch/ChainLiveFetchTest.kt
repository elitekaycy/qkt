package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.QuoteSource
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionListing
import com.qkt.marketdata.store.deribit.DeribitBookSnapshot
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainLiveFetchTest {
    private val root = "DERIBIT:BTC_USDC"
    private val call = "BTC_USDC-25DEC26-92000-C"

    private fun declare(dir: Path) =
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: $root, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )

    private fun snapshotAt(iso: String): DeribitBookSnapshot.Taken {
        val at = Instant.parse(iso).toEpochMilli()
        val quote =
            ChainQuote(
                at,
                call,
                BigDecimal("1490"),
                BigDecimal("1510"),
                BigDecimal("1500"),
                BigDecimal("50"),
                BigDecimal("83000"),
                null,
                0,
                QuoteSource.BOOK,
            )
        return DeribitBookSnapshot.Taken(ChainSnapshot(root, at, listOf(quote)), emptySet())
    }

    @Test
    fun `each live snapshot is added to its day`(
        @TempDir dir: Path,
    ) {
        declare(dir)
        OptionCatalogStore(dir).write(OptionCatalog(root, listOf(OptionListing(call, "92000", "call", 1798185600000))))

        ChainLiveFetch.run(root, dir) { _, _ -> snapshotAt("2026-10-01T09:00:00Z") }
        val code = ChainLiveFetch.run(root, dir) { _, _ -> snapshotAt("2026-10-01T09:15:00Z") }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(ChainSnapshotStore(dir, QuoteSource.BOOK).readDay(root, LocalDate.parse("2026-10-01"))).hasSize(2)
    }

    @Test
    fun `a root without a catalog is refused before asking the venue`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        assertThat(ChainLiveFetch.run(root, dir) { _, _ -> error("not called") }).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `the live flag needs no day range and refuses one`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        fun fetch(vararg extra: String) =
            ChainFetch.run(
                root,
                Args(arrayOf("fetch", root, "--chains", "--live", "--data-root", dir.toString(), *extra)),
            )

        assertThat(fetch()).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(fetch("--last", "2d")).isEqualTo(ExitCodes.ARG_ERROR)
    }

    @Test
    fun `an unreadable day file is a reported failure, not a crash`(
        @TempDir dir: Path,
    ) {
        declare(dir)
        OptionCatalogStore(dir).write(OptionCatalog(root, listOf(OptionListing(call, "92000", "call", 1798185600000))))
        val file = ChainSnapshotStore(dir, QuoteSource.BOOK).path(root, LocalDate.parse("2026-10-01"))
        Files.createDirectories(file.parent)
        Files.write(file, "not gzip".toByteArray())

        assertThat(
            ChainLiveFetch.run(root, dir) { _, _ -> snapshotAt("2026-10-01T09:00:00Z") },
        ).isEqualTo(ExitCodes.USER_ERROR)
    }
}
