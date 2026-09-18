package com.qkt.marketdata.store

import com.qkt.marketdata.source.MarketRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultDataStoreRangeResolutionTest {
    @TempDir lateinit var dir: Path

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private val day15: Long = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
    private val day16: Long = Instant.parse("2024-01-16T00:00:00Z").toEpochMilli()

    private fun writeDay(
        symbol: String,
        day: String,
        ticks: List<Pair<Long, String>>,
    ) {
        val symDir = dir.resolve("symbols").resolve(symbol)
        Files.createDirectories(symDir)
        val rows =
            buildString {
                appendLine(header)
                ticks.forEach { (ts, price) ->
                    appendLine("$ts,$symbol,$price,1,,,,")
                }
            }.trimEnd('\n')
        Files.writeString(symDir.resolve("$day.csv"), rows)
    }

    private fun writeManifest(
        symbol: String,
        ranges: List<Pair<String, String>>,
    ) {
        val store = ManifestStore(dir)
        store.write(Manifest(symbol = symbol, ranges = ranges.map { DayRange(it.first, it.second) }))
    }

    @Test
    fun `openFeed with null from and to resolves intersection of cached ranges`() {
        writeDay("A", "2024-01-15", listOf(day15 to "100"))
        writeDay("B", "2024-01-15", listOf(day15 to "100"))
        writeDay("B", "2024-01-16", listOf(day16 to "101"))
        writeManifest("A", listOf("2024-01-15" to "2024-01-16"))
        writeManifest("B", listOf("2024-01-15" to "2024-01-17"))
        val store = DefaultDataStore(root = dir)
        val request = MarketRequest(symbols = listOf("A", "B"))
        store.openFeed(request).use { feed ->
            val collected = generateSequence { feed.next() }.toList()
            assertThat(collected.map { it.timestamp }).containsExactly(day15, day15)
            assertThat(collected.map { it.symbol }).containsExactlyInAnyOrder("A", "B")
        }
    }

    @Test
    fun `openFeed with null from and to and empty cache throws clear error`() {
        val store = DefaultDataStore(root = dir)
        val request = MarketRequest(symbols = listOf("A", "B"))
        assertThatThrownBy { store.openFeed(request) }
            .hasMessageContaining("no cached data")
    }

    @Test
    fun `partial null from to with from beyond cache latest throws clear error`() {
        writeDay("X", "2024-01-15", listOf(day15 to "100"))
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.parse("2024-02-01T00:00:00Z"),
            )
        assertThatThrownBy { store.openFeed(request) }
            .hasMessageContaining("resolved range is empty")
    }

    @Test
    fun `partial null from to with to before cache earliest throws clear error`() {
        writeDay("X", "2024-01-15", listOf(day15 to "100"))
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                to = Instant.parse("2023-12-01T00:00:00Z"),
            )
        assertThatThrownBy { store.openFeed(request) }
            .hasMessageContaining("resolved range is empty")
    }
}
