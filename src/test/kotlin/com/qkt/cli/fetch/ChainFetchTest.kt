package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.OptionTrade
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionListing
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
            trade("3", "2026-09-30T11:00:00Z", "BTC_USDC-9OCT26-99000-C"),
        )
    private val asked = mutableListOf<Pair<Long, Long>>()

    private fun source(
        from: Long,
        to: Long,
    ): List<OptionTrade> {
        asked += from to to
        return feed.filter { it.timestampMs in from until to }
    }

    private fun run(
        dir: Path,
        from: String = "2026-09-30",
        to: String = "2026-09-30",
        maxAge: Long = 24 * hour,
    ) = ChainFetch.run(
        root,
        dir,
        ChainFetch.Window(LocalDate.parse(from), LocalDate.parse(to), hour, maxAge),
        LocalDate.parse("2026-10-01"),
    ) { _, from, to -> source(from, to) }

    @Test
    fun `a completed day is written hourly from trades reaching back the maximum mark age`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        assertThat(run(dir)).isEqualTo(ExitCodes.SUCCESS)

        val day = ChainSnapshotStore(dir).readDay(root, LocalDate.parse("2026-09-30"))
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
        assertThat(ChainSnapshotStore(dir).readDay(root, LocalDate.parse("2026-09-30")).first().quotes).hasSize(1)
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
    fun `a day that has not ended, a missing catalog and an uneven interval are refused`(
        @TempDir dir: Path,
    ) {
        declare(dir, catalogued = false)
        assertThat(run(dir)).isEqualTo(ExitCodes.USER_ERROR)

        declare(dir)
        assertThat(run(dir, to = "2026-10-01")).isEqualTo(ExitCodes.USER_ERROR)
        val uneven = ChainFetch.Window(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-30"), 7 * hour, hour)
        assertThat(
            ChainFetch.run(root, dir, uneven, LocalDate.parse("2026-10-01")) { _, f, t -> source(f, t) },
        ).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(asked).isEmpty()
    }
}
