package com.qkt.marketdata.store

import com.qkt.common.SessionAnchor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoreGuardsTest {
    @TempDir
    lateinit var dir: Path

    private fun manifest(
        version: Int = 1,
        schema: String = "qkt-bars-csv-v1",
    ) = """{"schemaVersion":$version,"schema":"$schema","broker":"B","symbol":"S","timeframe":"1m"}"""

    @Test
    fun `a bars manifest that is not JSON is refused as corrupt naming its path`() {
        val path = dir.resolve("manifest.json")

        assertThatThrownBy { BarsManifestCodec.decode(path, "{not json") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("corrupt bars manifest at $path")
    }

    @Test
    fun `a bars manifest of another schema version or schema is refused`() {
        val path = dir.resolve("manifest.json")

        assertThat(BarsManifestCodec.decode(path, manifest()).symbol).isEqualTo("S")
        assertThatThrownBy { BarsManifestCodec.decode(path, manifest(version = 2)) }
            .hasMessageContaining("unsupported bars manifest schemaVersion")
            .hasMessageContaining(": 2")
        assertThatThrownBy { BarsManifestCodec.decode(path, manifest(schema = "parquet")) }
            .hasMessageContaining("unsupported bars manifest schema at")
            .hasMessageContaining("parquet")
    }

    @Test
    fun `a corrupt tick manifest is refused with the way to rebuild it`() {
        val store = ManifestStore(dir)
        val path = TickStoreKeys.dir(dir, "EXNESS:XAUUSD").resolve("manifest.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "][")

        assertThatThrownBy { store.read("EXNESS:XAUUSD") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("corrupt manifest at $path")
            .hasMessageContaining("rebuildManifests()")
    }

    @Test
    fun `the unknown-hours calendar anchors every session at UTC midnight`() {
        val noon = Instant.parse("2026-03-04T12:30:00Z")
        val midnight = Instant.parse("2026-03-04T00:00:00Z").toEpochMilli()

        assertThat(UnknownHoursCalendar.anchorEpochFor(SessionAnchor.CurrentSession, noon)).isEqualTo(midnight)
        val range = UnknownHoursCalendar.rangeFor(SessionAnchor.CurrentSession, midnight + 5_000L)
        assertThat(range.from.toEpochMilli()).isEqualTo(midnight)
        assertThat(range.to).isEqualTo(Instant.parse("2026-03-05T00:00:00Z"))
    }

    @Test
    fun `a fetcher script that cannot be started is refused naming the script`() {
        val script = Files.writeString(dir.resolve("fetch.exe"), "")

        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("EXNESS:XAUUSD", LocalDate.of(2026, 1, 5), dir.resolve("out/day.bin"))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("could not start fetcher script")
            .hasMessageContaining("fetch.exe")
    }
}
