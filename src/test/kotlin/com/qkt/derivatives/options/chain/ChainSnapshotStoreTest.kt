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
        val store = ChainSnapshotStore(dir)

        store.write(root, listOf(second, first))

        assertThat(store.readDay(root, LocalDate.parse("2026-09-30"))).containsExactly(first)
        val day = store.readDay(root, LocalDate.parse("2026-10-01")).single()
        assertThat(
            day.quotes.map { it.contract },
        ).containsExactly("BTC_USDC-25DEC26-76000-P", "BTC_USDC-2OCT26-92000-C")
        assertThat(day.quotes.first { it.contract.endsWith("-P") }).isEqualTo(second.quotes[1])
        assertThat(
            store.path(root, LocalDate.parse("2026-10-01")).toString(),
        ).endsWith("chains/DERIBIT/BTC_USDC/2026-10-01.csv.gz")
    }

    @Test
    fun `the latest snapshot at or before an instant never comes from its future, even across midnight`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir)
        store.write(root, listOf(first, second))

        assertThat(store.latestAtOrBefore(root, ms("2026-10-01T00:00:00Z"))?.atMs).isEqualTo(second.atMs)
        assertThat(store.latestAtOrBefore(root, ms("2026-09-30T23:59:59Z"))?.atMs).isEqualTo(first.atMs)
        assertThat(store.latestAtOrBefore(root, ms("2026-09-30T22:59:59Z"))).isNull()
    }

    @Test
    fun `a missing day is empty and a corrupt one fails naming its file`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir)
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
        val store = ChainSnapshotStore(dir)
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
}
