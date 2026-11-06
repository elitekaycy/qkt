package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Imports refused before anything is written, each naming what to do instead. */
class ImportRefusalsTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun file(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.createDirectories(it.parent) }.also { Files.writeString(it, text) }

    private val bars =
        ImportDescriptor(
            kind = IngestKind.BARS,
            time = listOf("$0"),
            fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4"),
        )

    private fun refused(
        paths: List<Path>,
        symbol: String,
        descriptor: ImportDescriptor,
    ) = assertThatThrownBy { IngestFixtures.import(root, paths, symbol, descriptor, dryRun = true) }
        .isInstanceOf(ImportRefusal::class.java)

    @Test
    fun `a folder holding no data files is refused naming what it skipped`() {
        file("in/notes.md", "# notes\n")
        file("in/SHA256SUMS", "abc  x.csv\n")

        refused(listOf(dir.resolve("in")), "DATA:EURUSD", bars)
            .hasMessage("nothing to import (skipped: SHA256SUMS, notes.md)")
    }

    @Test
    fun `non-data files beside the data are counted by extension in the warnings`() {
        file("in/bars.csv", "2024-01-02T10:00:00Z,1,1,1,1\n2024-01-02T10:01:00Z,1,1,1,1\n")
        file("in/notes.md", "# notes\n")

        val report = IngestFixtures.import(root, listOf(dir.resolve("in")), "DATA:EURUSD", bars, dryRun = true)

        assertThat(report.warnings).contains("1 non-data file(s) skipped: 1 .md")
    }

    @Test
    fun `a field the kind does not have is refused listing its fields`() {
        val f = file("bars.csv", "2024-01-02T10:00:00Z,1,1,1,1\n2024-01-02T10:01:00Z,1,1,1,1\n")

        refused(listOf(f), "DATA:EURUSD", bars.copy(fields = bars.fields + ("bid" to "$1")))
            .hasMessageContaining("'bid' is not a bars field")
    }

    @Test
    fun `a field naming a column the file lacks is refused`() {
        val f = file("bars.csv", "2024-01-02T10:00:00Z,1,1,1,1\n2024-01-02T10:01:00Z,1,1,1,1\n")

        refused(listOf(f), "DATA:EURUSD", bars.copy(fields = bars.fields + ("close" to "settle")))
            .hasMessageContaining("cannot map close from 'settle'")
    }

    @Test
    fun `bars under the BACKTEST source are refused`() {
        val f = file("bars.csv", "2024-01-02T10:00:00Z,1,1,1,1\n2024-01-02T10:01:00Z,1,1,1,1\n")

        refused(listOf(f), "BACKTEST:EURUSD", bars).hasMessageContaining("BACKTEST names the bars")
    }

    @Test
    fun `a single bar without a stated timeframe is refused`() {
        val f = file("bars.csv", "2024-01-02T10:00:00Z,1,1,1,1\n")

        refused(listOf(f), "DATA:EURUSD", bars).hasMessageContaining("fewer than two bars; pass --tf")
    }

    @Test
    fun `bars half a second apart are refused as no timeframe`() {
        val stamps = listOf("00.000", "00.500", "01.000", "01.500")
        val rows = stamps.joinToString("\n", postfix = "\n") { "2024-01-02T10:00:${it}Z,1,1,1,1" }

        refused(listOf(file("bars.csv", rows)), "DATA:EURUSD", bars).hasMessageContaining("500ms apart")
    }

    @Test
    fun `a file of one column is refused asking for the delimiter`() {
        val f = file("one.csv", "1\n2\n3\n4\n")

        refused(listOf(f), "DATA:EURUSD", bars).hasMessageContaining("no delimiter splits the file into columns")
    }
}
