package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A day written before the `index` column still reads, and its next write adds the column. */
class ChainSnapshotLegacyFileTest {
    private val root = "DERIBIT:BTC_USDC"
    private val day = LocalDate.parse("2026-10-01")
    private val atMs = 1_790_812_800_000L

    @Test
    fun `a legacy day reads without an index and is upgraded when a snapshot is appended`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val file = store.path(root, day)
        Files.createDirectories(file.parent)
        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter().use {
            it.write("${ChainCsv.LEGACY_HEADER}\n$atMs,BTC_USDC-2OCT26-92000-C,,15.0,20,48.7,83502.39,,0,BOOK\n")
        }

        val legacy =
            store
                .readDay(root, day)
                .single()
                .quotes
                .single()
        assertThat(legacy.underlying).isEqualByComparingTo("83502.39")
        assertThat(legacy.index).isNull()

        val next = legacy.copy(atMs = atMs + 60_000, index = BigDecimal("83476.57"))
        store.append(root, ChainSnapshot(root, next.atMs, listOf(next)))

        val lines = GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { it.readLines() }
        assertThat(lines.first()).isEqualTo(ChainCsv.HEADER)
        assertThat(store.readDay(root, day).map { it.quotes.single().index }).containsExactly(
            null,
            BigDecimal("83476.57"),
        )
    }
}
