package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManifestStoreTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `write then read round trips a manifest`() {
        val store = ManifestStore(dir)
        val manifest =
            Manifest(
                symbol = "EURUSD",
                ranges = listOf(DayRange("2024-01-15", "2024-01-17")),
                lastUpdated = "2026-05-04T00:00:00Z",
            )
        store.write(manifest)
        val read = store.read("EURUSD")
        assertThat(read.symbol).isEqualTo("EURUSD")
        assertThat(read.ranges).hasSize(1)
        assertThat(read.ranges[0].from).isEqualTo("2024-01-15")
        assertThat(read.ranges[0].to).isEqualTo("2024-01-17")
    }

    @Test
    fun `read of missing manifest returns empty manifest with no ranges`() {
        val store = ManifestStore(dir)
        val read = store.read("UNKNOWN")
        assertThat(read.symbol).isEqualTo("UNKNOWN")
        assertThat(read.ranges).isEmpty()
    }

    @Test
    fun `unknown schemaVersion throws`() {
        val store = ManifestStore(dir)
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        Files.writeString(
            symDir.resolve("manifest.json"),
            """{"schemaVersion":2,"schema":"qkt-csv-v1","symbol":"X","ranges":[],"lastUpdated":""}""",
        )
        assertThatThrownBy { store.read("X") }.hasMessageContaining("unsupported manifest schemaVersion")
    }

    @Test
    fun `unknown schema name throws`() {
        val store = ManifestStore(dir)
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        Files.writeString(
            symDir.resolve("manifest.json"),
            """{"schemaVersion":1,"schema":"other","symbol":"X","ranges":[],"lastUpdated":""}""",
        )
        assertThatThrownBy { store.read("X") }.hasMessageContaining("unsupported manifest schema")
    }

    @Test
    fun `coalesce merges adjacent day ranges`() {
        val store = ManifestStore(dir)
        val merged =
            store.coalesce(
                listOf(DayRange("2024-01-01", "2024-01-08")),
                DayRange("2024-01-08", "2024-01-15"),
            )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].from).isEqualTo("2024-01-01")
        assertThat(merged[0].to).isEqualTo("2024-01-15")
    }

    @Test
    fun `coalesce keeps disjoint ranges separate`() {
        val store = ManifestStore(dir)
        val merged =
            store.coalesce(
                listOf(DayRange("2024-01-01", "2024-01-08")),
                DayRange("2024-01-15", "2024-01-22"),
            )
        assertThat(merged).hasSize(2)
    }

    @Test
    fun `coalesce handles range fully contained`() {
        val store = ManifestStore(dir)
        val merged =
            store.coalesce(
                listOf(DayRange("2024-01-01", "2024-01-15")),
                DayRange("2024-01-05", "2024-01-08"),
            )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].from).isEqualTo("2024-01-01")
        assertThat(merged[0].to).isEqualTo("2024-01-15")
    }

    @Test
    fun `atomic write does not leave half written file on success`() {
        val store = ManifestStore(dir)
        val manifest = Manifest(symbol = "X", ranges = emptyList(), lastUpdated = "")
        store.write(manifest)
        val symDir = dir.resolve("symbols").resolve("X")
        assertThat(Files.exists(symDir.resolve("manifest.json"))).isTrue()
        assertThat(Files.list(symDir).toList().none { it.fileName.toString().endsWith(".tmp") }).isTrue()
    }
}
