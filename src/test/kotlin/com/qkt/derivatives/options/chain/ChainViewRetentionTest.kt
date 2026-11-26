package com.qkt.derivatives.options.chain

import com.qkt.derivatives.options.chain.OptionChainFixture.Companion.ms
import com.qkt.instrument.QuoteSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A live view holds about one snapshot per root however many a day's file accumulates (#1782). */
class ChainViewRetentionTest {
    private val midnight = ms("2026-10-01T00:00:00Z")

    private fun later(
        first: ChainSnapshot,
        minute: Int,
    ): ChainSnapshot {
        val at = midnight + minute * 60_000L
        return ChainSnapshot(first.root, at, first.quotes.map { it.copy(atMs = at) })
    }

    @Test
    fun `a view asked at a live clock holds at most two snapshots after a day of minute snapshots`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-01T00:00:00Z", "100", 0L))
        val store = ChainSnapshotStore(dir, QuoteSource.TRADE)
        val view = ChainView(f.registry)
        val first = view.latest(f.root.root, midnight)!!

        for (minute in 1 until 600) {
            store.append(f.root.root, later(first, minute))
            assertThat(view.latest(f.root.root, midnight + minute * 60_000L + 30_000)?.atMs)
                .isEqualTo(midnight + minute * 60_000L)
            assertThat(view.heldSnapshots()).isLessThanOrEqualTo(2)
        }
        assertThat(store.readDay(f.root.root, LocalDate.parse("2026-10-01"))).hasSize(600)
    }

    @Test
    fun `an instant before what the view kept is answered from the file, not from what was dropped`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(
            Triple("2026-10-01T01:00:00Z", "100", 0L),
            Triple("2026-10-01T02:00:00Z", "110", 0L),
            Triple("2026-10-01T03:00:00Z", "120", 0L),
        )
        val view = ChainView(f.registry)

        assertThat(view.latest(f.root.root, ms("2026-10-01T03:30:00Z"))?.atMs).isEqualTo(ms("2026-10-01T03:00:00Z"))
        assertThat(view.heldSnapshots()).isEqualTo(1)
        assertThat(view.latest(f.root.root, ms("2026-10-01T01:30:00Z"))?.atMs).isEqualTo(ms("2026-10-01T01:00:00Z"))
        assertThat(view.latest(f.root.root, ms("2026-10-01T00:30:00Z"))).isNull()
    }

    @Test
    fun `a replay asking from midnight keeps the snapshots still ahead and drops each one it passes`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(
            Triple("2026-10-01T01:00:00Z", "100", 0L),
            Triple("2026-10-01T02:00:00Z", "110", 0L),
            Triple("2026-10-01T03:00:00Z", "120", 0L),
        )
        val view = ChainView(f.registry)

        assertThat(view.latest(f.root.root, ms("2026-10-01T00:10:00Z"))).isNull()
        assertThat(view.heldSnapshots()).isEqualTo(3)
        assertThat(view.latest(f.root.root, ms("2026-10-01T02:10:00Z"))?.atMs).isEqualTo(ms("2026-10-01T02:00:00Z"))
        assertThat(view.heldSnapshots()).isEqualTo(2)
    }

    @Test
    fun `an unreadable or headerless day file names the file`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-01T01:00:00Z", "100", 0L))
        val file = ChainSnapshotStore(dir, QuoteSource.TRADE).path(f.root.root, LocalDate.parse("2026-10-01"))
        Files.writeString(file, "not gzip")

        assertThatThrownBy { ChainView(f.registry).latest(f.root.root, ms("2026-10-01T02:00:00Z")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unreadable chain file")
    }

    @Test
    fun `a day file of another source or header is refused naming the file`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-01T01:00:00Z", "100", 0L))
        val file = ChainSnapshotStore(dir, QuoteSource.TRADE).path(f.root.root, LocalDate.parse("2026-10-01"))
        val at = ms("2026-10-01T01:00:00Z")
        val book = "$at,${f.venueName},,15.0,20,48.7,83502.39,,0,BOOK"
        val view = ChainView(f.registry)

        gzip(file, "${ChainCsv.HEADER}\n$book\n")
        assertThatThrownBy { view.latest(f.root.root, at) }.hasMessageContaining("invalid chain file")
        gzip(file, "atMs,contract\n$book\n")
        assertThatThrownBy { view.latest(f.root.root, at) }.hasMessageContaining("header is not")
    }

    @Test
    fun `a later snapshot of another source is refused rather than appended`(
        @TempDir dir: Path,
    ) {
        val f = OptionChainFixture(dir)
        f.store(Triple("2026-10-01T01:00:00Z", "100", 0L))
        val first = ChainView(f.registry).latest(f.root.root, ms("2026-10-01T01:00:00Z"))!!
        val next = later(first, 120)
        val book = next.copy(quotes = next.quotes.map { it.copy(source = QuoteSource.BOOK) })

        assertThatThrownBy { ChainSnapshotStore(dir, QuoteSource.TRADE).append(f.root.root, book) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun gzip(
        file: Path,
        text: String,
    ) = GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter().use { it.write(text) }
}
