package com.qkt.marketdata.depth

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Order-book snapshots stored a gzipped CSV a contract and day, exact, and the fields a strategy reads from them. */
class BookDepthStoreTest {
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val day = 1_791_158_400_000L // 2026-10-05T00:00Z

    private fun level(
        price: String,
        amount: String,
    ) = BookLevel(BigDecimal(price), BigDecimal(amount))

    private fun book(
        timeMs: Long,
        bid: String = "10.0",
    ) = BookDepth(timeMs, listOf(level("86472.7", bid), level("86200", "0.002")), listOf(level("86472.8", "4")))

    private fun gunzip(file: Path) = GZIPInputStream(Files.newInputStream(file)).bufferedReader().readLines()

    @Test
    fun `snapshots are stored exactly, a file a day, and read back oldest first within the range`(
        @TempDir root: Path,
    ) {
        val store = BookDepthStore(root)
        store.merge(perp, listOf(book(day + 20_000), book(day - 10_000), book(day + 10_000)))

        val file = root.resolve("depth/DERIBIT/BTC_USDC_PERPETUAL/2026-10-05.csv.gz")
        assertThat(gunzip(file))
            .containsExactly(
                "time,bids,asks",
                "${day + 10_000},86472.7:10.0 86200:0.002,86472.8:4",
                "${day + 20_000},86472.7:10.0 86200:0.002,86472.8:4",
            )
        assertThat(Files.exists(root.resolve("depth/DERIBIT/BTC_USDC_PERPETUAL/2026-10-04.csv.gz"))).isTrue
        assertThat(store.snapshots(perp, day - 10_000, day + 10_000).map { it.timeMs }).containsExactly(
            day - 10_000,
            day + 10_000,
        )
        assertThat(store.times(perp, day, day + 86_400_000)).containsExactly(day + 10_000, day + 20_000)
        assertThat(store.snapshots(perp, day, day + 10_000).single()).isEqualTo(book(day + 10_000))
    }

    @Test
    fun `a merge replaces a snapshot at the same time and keeps the rest`(
        @TempDir root: Path,
    ) {
        val store = BookDepthStore(root)
        store.merge(perp, listOf(book(day), book(day + 10_000)))

        val held = store.merge(perp, listOf(book(day + 10_000, bid = "3"), book(day + 20_000)))

        assertThat(held).isEqualTo(3)
        assertThat(
            store.snapshots(perp, day, day + 20_000).map {
                it.bids
                    .first()
                    .amount
                    .toPlainString()
            },
        ).containsExactly("10.0", "3", "10.0")
    }

    @Test
    fun `bid and ask depth sum the levels held and imbalance weighs them, zero for an empty book`() {
        val book = book(day)

        assertThat(book.bidDepth).isEqualByComparingTo("10.002")
        assertThat(book.askDepth).isEqualByComparingTo("4")
        assertThat(book.imbalance).isEqualByComparingTo("0.4286530495643480")
        assertThat(BookDepth(day, emptyList(), listOf(level("1", "2"))).imbalance).isEqualByComparingTo("-1")
        assertThat(BookDepth(day, emptyList(), emptyList()).imbalance).isEqualByComparingTo("0")
        assertThat(BookDepthSymbol.value(BookDepthSymbol.of("ask_depth", perp), book)).isEqualByComparingTo("4")
        assertThat(BookDepthSymbol.contract("DEPTH:IMBALANCE:$perp")).isEqualTo(perp)
        assertThat(BookDepthSymbol.contract("DEPTH:MID:$perp")).isNull()
        assertThat(BookDepthSymbol.contract("OI:$perp")).isNull()
    }

    @Test
    fun `a malformed line fails naming the file and line, and no file means no snapshots`(
        @TempDir root: Path,
    ) {
        val store = BookDepthStore(root)
        assertThat(store.snapshots(perp, 0, Long.MAX_VALUE / 2)).isEmpty()
        val file = root.resolve("depth/DERIBIT/BTC_USDC_PERPETUAL/2026-10-05.csv.gz")
        Files.createDirectories(file.parent)
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { it.write("time,bids,asks\n$day,86472.7:x,86472.8:4\n") }
        Files.write(file, bytes.toByteArray())

        assertThatThrownBy { store.snapshots(perp, day, day) }.hasMessageContaining("2026-10-05.csv.gz line 2")
    }
}
