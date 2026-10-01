package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionTrade
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionListing
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainFetchTest {
    private val root = "DERIBIT:BTC_USDC"
    private val call = "BTC_USDC-25DEC26-92000-C"
    private val hour = 3_600_000L

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun declare(
        dir: Path,
        catalogued: Boolean = true,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: $root, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )
        if (catalogued) {
            OptionCatalogStore(
                dir,
            ).write(OptionCatalog(root, listOf(OptionListing(call, "92000", "call", ms("2026-12-25T08:00:00Z")))))
        }
    }

    private fun trade(
        id: String,
        at: String,
        contract: String = call,
    ) = OptionTrade(id, ms(at), 1, contract, BigDecimal("1500"), BigDecimal("50"), BigDecimal("83000"))

    private val feed =
        listOf(
            trade("1", "2026-09-29T20:00:00Z"),
            trade("2", "2026-09-30T10:30:00Z"),
        )
    private val asked = mutableListOf<Pair<Long, Long>>()
    private val extra = mutableListOf<OptionTrade>()

    private fun source(
        from: Long,
        to: Long,
    ): List<OptionTrade> {
        asked += from to to
        return (feed + extra).filter { it.timestampMs in from until to }
    }

    private fun run(
        dir: Path,
        from: String = "2026-09-30",
        to: String = "2026-09-30",
        maxAge: Long = 24 * hour,
        every: Long = hour,
        now: String = "2026-10-01T00:10:00Z",
    ) = ChainFetch.run(
        root,
        dir,
        ChainFetch.Window(LocalDate.parse(from), LocalDate.parse(to), every, maxAge),
        ms(now),
    ) { _, from, to -> source(from, to) }

    @Test
    fun `a completed day is written hourly from trades reaching back the maximum mark age`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        assertThat(run(dir)).isEqualTo(ExitCodes.SUCCESS)

        val day = ChainSnapshotStore(dir, QuoteSource.TRADE).readDay(root, LocalDate.parse("2026-09-30"))
        assertThat(day).hasSize(24)
        assertThat(
            day
                .first()
                .quotes
                .single()
                .markAgeMs,
        ).isEqualTo(4 * hour)
        assertThat(
            day
                .last()
                .quotes
                .single()
                .mark,
        ).isEqualByComparingTo("1500")
        assertThat(asked.first()).isEqualTo(ms("2026-09-29T00:00:00Z") to ms("2026-10-01T00:00:00Z"))
    }

    @Test
    fun `consecutive days fetch each trade once and carry the look-back between them`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        run(dir, from = "2026-09-29", to = "2026-09-30")

        assertThat(asked).containsExactly(
            ms("2026-09-28T00:00:00Z") to ms("2026-09-30T00:00:00Z"),
            ms("2026-09-30T00:00:00Z") to ms("2026-10-01T00:00:00Z"),
        )
        assertThat(
            ChainSnapshotStore(dir, QuoteSource.TRADE).readDay(root, LocalDate.parse("2026-09-30")).first().quotes,
        ).hasSize(1)
    }

    @Test
    fun `a day already on disk is skipped`(
        @TempDir dir: Path,
    ) {
        declare(dir)
        run(dir)
        asked.clear()

        assertThat(run(dir)).isEqualTo(ExitCodes.SUCCESS)

        assertThat(asked).isEmpty()
    }

    @Test
    fun `a day is fetched only once it ended five minutes ago, and the interval is at least a minute`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        assertThat(run(dir, now = "2026-10-01T00:04:59Z")).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run(dir, to = "2026-10-01")).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run(dir, every = 30_000)).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run(dir, every = 7 * hour)).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a root without a catalog is refused before asking the venue`(
        @TempDir dir: Path,
    ) {
        declare(dir, catalogued = false)

        assertThat(run(dir)).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a day trading contracts the catalog lacks is refused, not written incomplete`(
        @TempDir dir: Path,
    ) {
        declare(dir)
        extra += trade("3", "2026-09-30T11:00:00Z", "BTC_USDC-9OCT26-99000-C")

        assertThat(run(dir)).isEqualTo(ExitCodes.USER_ERROR)

        assertThat(ChainSnapshotStore(dir, QuoteSource.TRADE).hasDay(root, LocalDate.parse("2026-09-30"))).isFalse()
    }

    @Test
    fun `a day holding live book snapshots is still backfilled from trades`(
        @TempDir dir: Path,
    ) {
        declare(dir)
        val at = ms("2026-09-30T06:00:00Z")
        val bookQuote =
            ChainQuote(
                at,
                call,
                BigDecimal("1490"),
                BigDecimal("1510"),
                BigDecimal("1500"),
                null,
                BigDecimal("83000"),
                null,
                0,
                QuoteSource.BOOK,
            )
        ChainSnapshotStore(dir, QuoteSource.BOOK).write(root, listOf(ChainSnapshot(root, at, listOf(bookQuote))))

        run(dir)

        assertThat(ChainSnapshotStore(dir, QuoteSource.TRADE).readDay(root, LocalDate.parse("2026-09-30"))).hasSize(24)
    }
}
