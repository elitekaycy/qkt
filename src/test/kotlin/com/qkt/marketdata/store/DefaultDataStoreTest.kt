package com.qkt.marketdata.store

import com.qkt.marketdata.source.MarketRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultDataStoreTest {
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
    fun `dayFile resolves csv when present`() {
        writeDay("X", "2024-01-15", listOf(day15 to "100"))
        val store = DefaultDataStore(root = dir)
        val path = store.dayFile("X", LocalDate.parse("2024-01-15"))
        assertThat(path).isNotNull
        assertThat(path!!.fileName.toString()).isEqualTo("2024-01-15.csv")
    }

    @Test
    fun `dayFile returns null when neither csv nor csvgz exists`() {
        val store = DefaultDataStore(root = dir)
        assertThat(store.dayFile("X", LocalDate.parse("2024-01-15"))).isNull()
    }

    @Test
    fun `openFeed with explicit range and cached data streams ticks`() {
        writeDay("X", "2024-01-15", listOf(day15 + 1L to "100", day15 + 2L to "101"))
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-16T00:00:00Z"),
            )
        store.openFeed(request).use { feed ->
            assertThat(feed.next()!!.timestamp).isEqualTo(day15 + 1L)
            assertThat(feed.next()!!.timestamp).isEqualTo(day15 + 2L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `openFeed with no fetcher and missing days throws clear error`() {
        val store = DefaultDataStore(root = dir, fetcher = null)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-16T00:00:00Z"),
            )
        assertThatThrownBy { store.openFeed(request) }
            .hasMessageContaining("missing data")
            .hasMessageContaining("X")
    }

    @Test
    fun `openFeed with explicit range and missing days invokes fetcher`() {
        val fetched = mutableListOf<Pair<String, LocalDate>>()
        val fetcher =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) {
                    fetched.add(symbol to day)
                    Files.createDirectories(target.parent)
                    val ts = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    GZIPOutputStream(Files.newOutputStream(target)).use {
                        it.write("$header\n$ts,$symbol,100,1,,,,".toByteArray())
                    }
                }
            }
        val store = DefaultDataStore(root = dir, fetcher = fetcher)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        store.openFeed(request).use { feed ->
            generateSequence { feed.next() }.toList()
        }
        assertThat(fetched).containsExactly(
            "X" to LocalDate.parse("2024-01-15"),
            "X" to LocalDate.parse("2024-01-16"),
        )
    }

    @Test
    fun `openFeed merges multiple symbols by timestamp`() {
        writeDay("A", "2024-01-15", listOf(day15 + 1L to "100", day15 + 3L to "102"))
        writeDay("B", "2024-01-15", listOf(day15 + 2L to "200", day15 + 4L to "202"))
        writeManifest("A", listOf("2024-01-15" to "2024-01-16"))
        writeManifest("B", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request =
            MarketRequest(
                symbols = listOf("A", "B"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-16T00:00:00Z"),
            )
        store.openFeed(request).use { feed ->
            val collected = generateSequence { feed.next() }.toList()
            assertThat(collected.map { it.timestamp })
                .containsExactly(day15 + 1L, day15 + 2L, day15 + 3L, day15 + 4L)
            assertThat(collected.map { it.symbol }).containsExactly("A", "B", "A", "B")
        }
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
    fun `openFeed clips to non day aligned from and to`() {
        writeDay(
            "X",
            "2024-01-15",
            listOf(
                day15 to "100",
                day15 + 12 * 3_600_000L to "101",
                day15 + 18 * 3_600_000L to "102",
            ),
        )
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.parse("2024-01-15T10:00:00Z"),
                to = Instant.parse("2024-01-15T17:00:00Z"),
            )
        store.openFeed(request).use { feed ->
            val collected = generateSequence { feed.next() }.toList()
            assertThat(collected).hasSize(1)
            assertThat(collected[0].timestamp).isEqualTo(day15 + 12 * 3_600_000L)
        }
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

    @Test
    fun `prefetch fills missing days without opening feed`() {
        var fetched = 0
        val fetcher =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) {
                    fetched++
                    Files.createDirectories(target.parent)
                    val ts = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    Files.writeString(target, "$header\n$ts,$symbol,100,1,,,,")
                }
            }
        val store = DefaultDataStore(root = dir, fetcher = fetcher)
        val request =
            MarketRequest(
                symbols = listOf("X"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        store.prefetch(request)
        assertThat(fetched).isEqualTo(2)
    }

    @Test
    fun `rebuildManifests clears stale ranges when a symbol has no day files`() {
        // A manifest claims coverage but the day file is gone — e.g. the provisioner deleted it
        // to force a refetch. rebuildManifests must drop the stale range so a later prefetch
        // re-materializes the day; leaving it would make the store skip the refetch forever.
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        Files.createDirectories(dir.resolve("symbols").resolve("X"))
        val store = DefaultDataStore(root = dir)
        store.rebuildManifests()
        assertThat(store.manifest("X").ranges).isEmpty()
    }

    @Test
    fun `concurrent provisioning of distinct days keeps every committed day`() {
        // Two store instances model two processes provisioning the same symbol at once. The manifest
        // update is a read-modify-write; without serializing + re-reading inside the lock, the second
        // writer would coalesce onto its stale pre-fetch snapshot and drop the first writer's day.
        val fetcher =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) {
                    Files.createDirectories(target.parent)
                    val ts = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    GZIPOutputStream(Files.newOutputStream(target)).use {
                        it.write("$header\n$ts,$symbol,100,1,,,,".toByteArray())
                    }
                }
            }
        val a = DefaultDataStore(root = dir, fetcher = fetcher)
        val b = DefaultDataStore(root = dir, fetcher = fetcher)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa =
                pool.submit {
                    barrier.await()
                    a.prefetch(
                        MarketRequest(
                            symbols = listOf("X"),
                            from = Instant.parse("2024-01-15T00:00:00Z"),
                            to = Instant.parse("2024-01-16T00:00:00Z"),
                        ),
                    )
                }
            val fb =
                pool.submit {
                    barrier.await()
                    b.prefetch(
                        MarketRequest(
                            symbols = listOf("X"),
                            from = Instant.parse("2024-01-16T00:00:00Z"),
                            to = Instant.parse("2024-01-17T00:00:00Z"),
                        ),
                    )
                }
            fa.get(30, TimeUnit.SECONDS)
            fb.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val ranges = DefaultDataStore(root = dir).manifest("X").ranges
        assertThat(ranges).hasSize(1)
        assertThat(ranges[0].from).isEqualTo("2024-01-15")
        assertThat(ranges[0].to).isEqualTo("2024-01-17")
    }

    @Test
    fun `dropDay deletes the day file and removes its manifest range`() {
        writeDay("X", "2024-01-15", listOf(day15 + 1L to "100"))
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        store.dropDay("X", LocalDate.parse("2024-01-15"))
        assertThat(store.dayFile("X", LocalDate.parse("2024-01-15"))).isNull()
        assertThat(store.manifest("X").ranges).isEmpty()
    }
}
