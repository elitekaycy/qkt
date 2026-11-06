package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.io.FileOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainSnapshotStoreFilesTest {
    private val root = "DERIBIT:BTC_USDC"
    private val day = LocalDate.of(2026, 10, 1)

    private fun quote(source: QuoteSource) =
        ChainQuote(
            0L,
            "BTC_USDC-2OCT26-92000-C",
            null,
            null,
            BigDecimal("100"),
            null,
            BigDecimal("80000"),
            null,
            0L,
            source,
        )

    private fun writeGzip(
        file: Path,
        lines: List<String>,
    ) {
        Files.createDirectories(file.parent)
        GZIPOutputStream(FileOutputStream(file.toFile())).bufferedWriter().use { out ->
            lines.forEach {
                out.write(
                    it + "\n",
                )
            }
        }
    }

    @Test
    fun `a store refuses quotes of the other source`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.TRADE)

        assertThatThrownBy { store.write(root, listOf(ChainSnapshot(root, 0L, listOf(quote(QuoteSource.BOOK))))) }
            .hasMessage("a TRADE chain store cannot hold BOOK quotes (BTC_USDC-2OCT26-92000-C)")
    }

    @Test
    fun `a missing day reads empty and a malformed one names the file`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        assertThat(store.readDayAfter(root, day, emptyList())).isEmpty()

        writeGzip(store.path(root, day), listOf("not,a,header"))
        assertThatThrownBy { store.readDayAfter(root, day, emptyList()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("invalid chain file: header is not")
    }

    @Test
    fun `a day holding the other source's quotes is invalid`(
        @TempDir dir: Path,
    ) {
        val trade = ChainSnapshotStore(dir, QuoteSource.TRADE)
        writeGzip(trade.path(root, day), listOf(ChainCsv.HEADER, ChainCsv.row(quote(QuoteSource.BOOK))))

        assertThatThrownBy { trade.readDayAfter(root, day, emptyList()) }
            .hasMessageContaining("holds quotes of a source other than TRADE")
    }

    @Test
    fun `a file that is not gzip is unreadable`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val file = store.path(root, day)
        Files.createDirectories(file.parent)
        Files.writeString(file, "plain text")

        assertThatThrownBy { store.readDayAfter(root, day, emptyList()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unreadable chain file")
    }
}
