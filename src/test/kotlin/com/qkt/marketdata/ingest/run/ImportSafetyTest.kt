package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.ManifestStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportSafetyTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")
    private val bars get() = root.resolve("bars/EXNESS/XAUUSD/1m")

    private fun mt5() = IngestFixtures.sample("mt5-bars.csv", dir.resolve("in"))

    @Test
    fun `a dry run writes nothing`() {
        val report = IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD", dryRun = true)

        assertThat(report.records).isEqualTo(5)
        assertThat(Files.exists(root)).isFalse()
    }

    @Test
    fun `a refused import leaves the store as it was`() {
        val bad = dir.resolve("in/bad.csv")
        Files.createDirectories(bad.parent)
        Files.writeString(bad, "time,open,high,low,close\n1704153600000,1,2,1,2\n1704240000000,1,2,1,x\n")

        val mapping =
            ImportDescriptor(
                kind = IngestKind.BARS,
                time = listOf("time"),
                fields = mapOf("open" to "open", "high" to "high", "low" to "low", "close" to "close"),
            )

        assertThatThrownBy { IngestFixtures.import(root, listOf(bad), "SRC:XAUUSD", mapping) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("could not be read")
        assertThat(Files.exists(root)).isFalse()
    }

    @Test
    fun `the same input always writes the same bytes`() {
        IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD")
        val other = dir.resolve("root2")
        IngestFixtures.import(other, listOf(mt5()), "EXNESS:XAUUSD")

        val a = IngestFixtures.dataFiles(root).map { Files.readAllBytes(it).toList() }
        val b = IngestFixtures.dataFiles(other).map { Files.readAllBytes(it).toList() }
        assertThat(a).isNotEmpty().isEqualTo(b)
    }

    @Test
    fun `re-importing the same file changes nothing`() {
        IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD")
        val before = IngestFixtures.dataFiles(root).associateWith { Files.getLastModifiedTime(it) }

        val again = IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD")

        assertThat(again.outcomes.unchanged).containsExactly(LocalDate.of(2024, 3, 8), LocalDate.of(2024, 3, 10))
        assertThat(again.outcomes.written).isEmpty()
        assertThat(IngestFixtures.dataFiles(root).associateWith { Files.getLastModifiedTime(it) }).isEqualTo(before)
    }

    @Test
    fun `stored days from elsewhere refuse, skip or are replaced with a backup`() {
        Files.createDirectories(bars)
        val fetched = bars.resolve("2024-03-10.csv")
        Files.writeString(fetched, "timestamp,open,high,low,close,volume\n")

        assertThatThrownBy { IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD") }
            .hasMessageContaining("already stored")
            .satisfies({ assertThat((it as ImportRefusal).lines.single()).contains("2024-03-10 (not imported") })

        val skipped = IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD", onOverlap = OverlapPolicy.SKIP)
        assertThat(skipped.outcomes.skipped).containsExactly(LocalDate.of(2024, 3, 10))
        assertThat(Files.readString(fetched)).isEqualTo("timestamp,open,high,low,close,volume\n")

        val replaced = IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD", onOverlap = OverlapPolicy.REPLACE)
        assertThat(replaced.outcomes.replaced).containsExactly(LocalDate.of(2024, 3, 10))
        assertThat(Files.readAllLines(fetched)).hasSize(4)
        val backups = Files.walk(bars.resolve(".import-backup")).use { s -> s.filter(Files::isRegularFile).toList() }
        assertThat(backups.single().fileName.toString()).isEqualTo("2024-03-10.csv")
    }

    @Test
    fun `bars fill the closed day inside their span and the manifest covers every day`() {
        val report = IngestFixtures.import(root, listOf(mt5()), "EXNESS:XAUUSD")

        assertThat(report.outcomes.emptyFilled).containsExactly(LocalDate.of(2024, 3, 9))
        assertThat(
            Files.readAllLines(bars.resolve("2024-03-09.csv")),
        ).containsExactly("timestamp,open,high,low,close,volume")
        val manifest = LocalBarStore(root).readManifest("EXNESS", "XAUUSD", "1m")
        assertThat(manifest.ranges.map { it.from to it.to }).containsExactly("2024-03-08" to "2024-03-10")
        assertThat(LocalBarStore(root).readDay("EXNESS", "XAUUSD", "1m", LocalDate.of(2024, 3, 10))).hasSize(3)
    }

    @Test
    fun `ticks land in the bare symbol's directory with a half-open manifest and provenance`() {
        val ticks = IngestFixtures.sample("mt5-ticks.csv", dir.resolve("in"))
        IngestFixtures.import(root, listOf(ticks), "EXNESS:XAUUSD")

        val manifest = ManifestStore(root).read("XAUUSD")
        assertThat(manifest.ranges.map { it.from to it.to }).containsExactly("2024-03-10" to "2024-03-11")
        val provenance = ImportProvenance.read(root.resolve("symbols/XAUUSD"))
        assertThat(provenance.entryFor("2024-03-10")?.preset).isEqualTo("mt5-ticks")
        assertThat(provenance.entryFor("2024-03-10")?.clock).isEqualTo("ny-close")
    }
}
