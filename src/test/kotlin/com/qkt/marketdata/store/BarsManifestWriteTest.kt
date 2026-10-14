package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarsManifestWriteTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `parallel writers recording different days lose none of them`() {
        val stores = (0 until 4).map { LocalBarStore(root) }
        val pool = Executors.newFixedThreadPool(4)
        val start = LocalDate.of(2024, 1, 1)
        (0 until 60).forEach { i ->
            pool.submit { stores[i % 4].recordDay("SRC", "X", "1m", start.plusDays(i * 2L)) }
        }
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

        val ranges = LocalBarStore(root).readManifest("SRC", "X", "1m").ranges
        assertThat(ranges).hasSize(60)
        assertThat(Files.list(root.resolve("bars/SRC/X/1m")).use { s -> s.map { it.fileName.toString() }.toList() })
            .containsExactly("manifest.json")
    }

    @Test
    fun `a manifest another tool annotated still reads`() {
        val dir = root.resolve("bars/SRC/X/1m").also { Files.createDirectories(it) }
        Files.writeString(
            dir.resolve("manifest.json"),
            """{"schemaVersion":1,"schema":"qkt-bars-csv-v1","broker":"SRC","symbol":"X","timeframe":"1m",""" +
                """"ranges":[{"from":"2024-01-01","to":"2024-01-02"}],"lastUpdated":"","source":"vendor"}""",
        )

        assertThat(LocalBarStore(root).readManifest("SRC", "X", "1m").ranges).hasSize(1)
    }
}
