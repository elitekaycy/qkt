package com.qkt.derivatives.options.chain

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainSnapshotStoreTest {
    private val root = "DERIBIT:BTC_USDC"

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun quote(
        at: String,
        contract: String,
        mark: String,
        bid: String? = null,
        ask: String? = null,
        age: Long = 0,
    ) = ChainQuote(
        atMs = ms(at),
        contract = contract,
        bid = bid?.let(::BigDecimal),
        ask = ask?.let(::BigDecimal),
        mark = BigDecimal(mark),
        markIv = BigDecimal("48.7"),
        underlying = BigDecimal("83551.57"),
        rate = null,
        markAgeMs = age,
        source = QuoteSource.BOOK,
    )

    private val first =
        ChainSnapshot(
            root,
            ms("2026-09-30T23:00:00Z"),
            listOf(quote("2026-09-30T23:00:00Z", "BTC_USDC-2OCT26-92000-C", "0.25", ask = "15")),
        )
    private val second =
        ChainSnapshot(
            root,
            ms("2026-10-01T00:00:00Z"),
            listOf(
                quote("2026-10-01T00:00:00Z", "BTC_USDC-2OCT26-92000-C", "0.3", bid = "0.1", ask = "15"),
                quote("2026-10-01T00:00:00Z", "BTC_USDC-25DEC26-76000-P", "5023.37797029", age = 3_600_000),
            ),
        )

    @Test
    fun `snapshots round-trip per UTC day with absent sides left empty`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)

        store.write(root, listOf(second, first))

        assertThat(store.readDay(root, LocalDate.parse("2026-09-30"))).containsExactly(first)
        val day = store.readDay(root, LocalDate.parse("2026-10-01")).single()
        assertThat(
            day.quotes.map { it.contract },
        ).containsExactly("BTC_USDC-25DEC26-76000-P", "BTC_USDC-2OCT26-92000-C")
        assertThat(day.quotes.first { it.contract.endsWith("-P") }).isEqualTo(second.quotes[1])
        assertThat(
            store.path(root, LocalDate.parse("2026-10-01")).toString(),
        ).endsWith("chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz")
    }

    @Test
    fun `the latest snapshot at or before an instant never comes from its future, even across midnight`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        store.write(root, listOf(first, second))

        assertThat(store.latestAtOrBefore(root, ms("2026-10-01T00:00:00Z"))?.atMs).isEqualTo(second.atMs)
        assertThat(store.latestAtOrBefore(root, ms("2026-09-30T23:59:59Z"))?.atMs).isEqualTo(first.atMs)
        assertThat(store.latestAtOrBefore(root, ms("2026-09-30T22:59:59Z"))).isNull()
    }

    @Test
    fun `a missing day is empty and a corrupt one fails naming its file`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        assertThat(store.readDay(root, LocalDate.parse("2026-10-02"))).isEmpty()

        val file = store.path(root, LocalDate.parse("2026-10-02"))
        Files.createDirectories(file.parent)
        Files.write(file, "not gzip".toByteArray())

        assertThatThrownBy { store.readDay(root, LocalDate.parse("2026-10-02")) }.hasMessageContaining(file.toString())
    }

    @Test
    fun `a snapshot holds at least one quote, one per contract, all of its own instant`() {
        val at = ms("2026-10-01T00:00:00Z")
        val q = quote("2026-10-01T00:00:00Z", "BTC_USDC-2OCT26-92000-C", "0.3")

        assertThatThrownBy { ChainSnapshot(root, at, emptyList()) }.hasMessageContaining("no quotes")
        assertThatThrownBy { ChainSnapshot(root, at, listOf(q, q)) }.hasMessageContaining("repeats a contract")
        assertThatThrownBy { ChainSnapshot(root, at + 1, listOf(q)) }.hasMessageContaining("another instant")
    }

    @Test
    fun `appending a snapshot keeps the day's others and replaces one at the same instant`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        store.write(root, listOf(second))
        val later =
            ChainSnapshot(
                root,
                ms("2026-10-01T01:00:00Z"),
                listOf(quote("2026-10-01T01:00:00Z", "BTC_USDC-2OCT26-92000-C", "0.4")),
            )
        val redo =
            ChainSnapshot(root, second.atMs, listOf(quote("2026-10-01T00:00:00Z", "BTC_USDC-2OCT26-92000-C", "0.35")))

        store.append(root, later)
        store.append(root, redo)

        assertThat(store.readDay(root, LocalDate.parse("2026-10-01"))).containsExactly(redo, later)
    }

    @Test
    fun `each source keeps its own days and refuses the other's quotes`(
        @TempDir dir: Path,
    ) {
        val book = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val trades = ChainSnapshotStore(dir, QuoteSource.TRADE)
        book.write(root, listOf(second))

        assertThat(trades.hasDay(root, LocalDate.parse("2026-10-01"))).isFalse()
        assertThat(trades.readDay(root, LocalDate.parse("2026-10-01"))).isEmpty()
        assertThatThrownBy { trades.write(root, listOf(second)) }.hasMessageContaining("BOOK")
    }

    @Test
    fun `decimals come back with their exact digits and scale`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val at = "2026-10-01T02:00:00Z"
        val exact =
            ChainSnapshot(
                root,
                ms(at),
                listOf(quote(at, "BTC_USDC-2OCT26-92000-C", "1E+3", bid = "1E-8", ask = "15.0")),
            )

        store.write(root, listOf(exact))

        assertThat(store.readDay(root, LocalDate.parse("2026-10-01"))).containsExactly(exact)
    }

    @Test
    fun `a file with a foreign header or a repeated row fails naming the file`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val day = LocalDate.parse("2026-10-01")
        store.write(root, listOf(second))
        val file = store.path(root, day)
        val lines =
            java.util.zip
                .GZIPInputStream(Files.newInputStream(file))
                .bufferedReader()
                .readLines()

        fun rewrite(content: List<String>) =
            java.util.zip.GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter().use {
                it.write(content.joinToString("\n"))
            }

        rewrite(lines.drop(1))
        assertThatThrownBy {
            store.readDay(
                root,
                day,
            )
        }.hasMessageContaining(file.toString()).hasMessageContaining("header")
        rewrite(lines + lines[1])
        assertThatThrownBy {
            store.readDay(
                root,
                day,
            )
        }.hasMessageContaining(file.toString()).hasMessageContaining("repeats")
    }
}
