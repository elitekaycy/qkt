package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Chain analytics streams live, over a copy of the real `btc-usdc-book-20261001` snapshot. */
class ChainAnalyticsLiveTest {
    private val fixture = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-20261001")).toURI())
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
        )
    private val at = 1_790_822_716_427L
    private val day = LocalDate.parse("2026-10-01")

    private fun copy(dir: Path) {
        Files.walk(fixture).use { paths ->
            paths.filter(Files::isRegularFile).forEach { file ->
                val target = dir.resolve(fixture.relativize(file).toString())
                Files.createDirectories(target.parent)
                Files.copy(file, target)
            }
        }
    }

    @Test
    fun `only a snapshot appended after the start ticks, with the value a backtest gives it`(
        @TempDir dir: Path,
    ) {
        copy(dir)
        val catalog =
            Json.decodeFromString(
                OptionCatalog.serializer(),
                dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
            )
        val registry = OptionCatalogRegistry(listOf(root), mapOf(root.root to catalog), dir)
        val source = ChainAnalyticsMarketSource(registry, FixedClock(at + 120_000), pollMs = 20)
        val feed = source.liveTicks(listOf("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d"))
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        val stored = store.readDay(root.root, day).single()
        val later = at + 60_000

        store.append(root.root, ChainSnapshot(root.root, later, stored.quotes.map { it.copy(atMs = later) }))

        val tick: Tick = feed.next()!!
        feed.close()
        val replayed =
            source
                .ticks(
                    tick.symbol,
                    TimeRange(Instant.ofEpochMilli(at), Instant.ofEpochMilli(later + 1)),
                ).toList()
        assertThat(replayed.map { it.timestamp }).containsExactly(at, later)
        assertThat(tick).isEqualTo(replayed.last())
    }
}
