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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultDataStoreProvisioningTest {
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
