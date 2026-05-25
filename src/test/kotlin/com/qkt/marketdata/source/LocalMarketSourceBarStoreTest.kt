package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.Manifest
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integration: when a [LocalBarStore] is wired into [LocalMarketSource], `bars()`
 * pulls from disk instead of aggregating ticks. Partial coverage falls back so
 * the two stores coexist safely.
 */
class LocalMarketSourceBarStoreTest {
    private val day = LocalDate.parse("2024-01-15")
    private val dayStart = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    private val dayEnd = dayStart.plusSeconds(86_400)

    /** No-op tick store — bar-store reads never touch it. */
    private fun emptyDataStore(rootPath: Path): DataStore =
        object : DataStore {
            override val root: Path = rootPath

            override fun manifest(symbol: String): Manifest = Manifest(symbol = symbol)

            override fun dayFile(
                symbol: String,
                day: LocalDate,
            ): Path? = null

            override fun openFeed(request: MarketRequest): TickFeed =
                object : TickFeed {
                    override fun next(): Tick? = null

                    override fun close() {}
                }

            override fun resolveRange(request: MarketRequest): Pair<Instant, Instant> =
                request.from!! to request.to!!

            override fun prefetch(request: MarketRequest) {}

            override fun rebuildManifests() {}
        }

    private fun bar(
        startMs: Long,
        close: String = "100.5",
    ): Candle =
        Candle(
            symbol = "EXNESS:XAUUSD",
            open = BigDecimal("100"),
            high = BigDecimal("101"),
            low = BigDecimal("99"),
            close = BigDecimal(close),
            volume = BigDecimal("1.0"),
            startTime = startMs,
            endTime = startMs + 60_000L,
        )

    @Test
    fun `bars are read from the bar store when fully covered`(
        @TempDir tmp: Path,
    ) {
        val barStore = LocalBarStore(root = tmp)
        val bars = (0..2).map { bar(dayStart.toEpochMilli() + it * 60_000L, close = "10$it") }
        barStore.writeDay("EXNESS", "XAUUSD", "1m", day, bars)

        val source =
            LocalMarketSource(
                store = emptyDataStore(tmp),
                clock = FixedClock(time = dayEnd.toEpochMilli()),
                barStore = barStore,
            )

        val out =
            source
                .bars("EXNESS:XAUUSD", TimeWindow.ONE_MINUTE, TimeRange(dayStart, dayEnd))
                .toList()

        assertThat(out).hasSize(3)
        assertThat(out[0].close).isEqualByComparingTo("100")
        assertThat(out[2].close).isEqualByComparingTo("102")
        assertThat(out[0].symbol).isEqualTo("EXNESS:XAUUSD")
    }

    @Test
    fun `bars are filtered to the requested range when reading from the bar store`(
        @TempDir tmp: Path,
    ) {
        val barStore = LocalBarStore(root = tmp)
        val bars = (0..5).map { bar(dayStart.toEpochMilli() + it * 60_000L, close = "20$it") }
        barStore.writeDay("EXNESS", "XAUUSD", "1m", day, bars)

        val source =
            LocalMarketSource(
                store = emptyDataStore(tmp),
                clock = FixedClock(time = dayEnd.toEpochMilli()),
                barStore = barStore,
            )

        val partialFrom = Instant.ofEpochMilli(dayStart.toEpochMilli() + 2 * 60_000L)
        val partialTo = Instant.ofEpochMilli(dayStart.toEpochMilli() + 5 * 60_000L)
        val out =
            source
                .bars("EXNESS:XAUUSD", TimeWindow.ONE_MINUTE, TimeRange(partialFrom, partialTo))
                .toList()

        assertThat(out.map { it.close.toPlainString() }).containsExactly("202", "203", "204")
    }

    @Test
    fun `falls back to tick aggregation when bar store has no data for the day`(
        @TempDir tmp: Path,
    ) {
        val barStore = LocalBarStore(root = tmp) // empty
        val source =
            LocalMarketSource(
                store = emptyDataStore(tmp),
                clock = FixedClock(time = dayEnd.toEpochMilli()),
                barStore = barStore,
            )

        // No bars in bar store, no ticks in tick store → empty result. The point is
        // that calling bars() doesn't throw and doesn't return phantom data.
        val out =
            source
                .bars("EXNESS:XAUUSD", TimeWindow.ONE_MINUTE, TimeRange(dayStart, dayEnd))
                .toList()

        assertThat(out).isEmpty()
    }
}
