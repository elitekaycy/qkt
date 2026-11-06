package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Per-day validation outcomes of an import: problems refuse, repeats are dropped and counted. */
class ImportDayChecksTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun file(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    private fun refusal(block: () -> Unit): ImportRefusal =
        try {
            block()
            throw AssertionError("expected a refusal")
        } catch (e: ImportRefusal) {
            e
        }

    @Test
    fun `a bar with a price of zero is a problem that refuses the import`() {
        val bars =
            ImportDescriptor(
                kind = IngestKind.BARS,
                time = listOf("$0"),
                fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4"),
            )
        val rows = listOf("10:00:00Z,1,1,1,1", "10:01:00Z,0,1,0,1", "10:02:00Z,1,1,1,1")
        val f = file("bars.csv", rows.joinToString("") { "2024-01-02T$it\n" })

        val e = refusal { IngestFixtures.import(root, listOf(f), "DATA:EURUSD", bars, dryRun = true) }

        assertThat(e.message).contains("problem(s) in the data")
        assertThat(e.lines).anySatisfy { assertThat(it).contains("a price is not positive") }
    }

    @Test
    fun `a negative open interest is a problem that refuses the import`() {
        val oi =
            ImportDescriptor(
                kind = IngestKind.OPEN_INTEREST,
                time = listOf("$0"),
                fields = mapOf("openInterest" to "$1"),
            )
        val f = file("oi.csv", "2024-01-02T10:00:00Z,100\n2024-01-02T10:05:00Z,-3\n")

        val e = refusal { IngestFixtures.import(root, listOf(f), "DATA:BTCUSDT", oi, dryRun = true) }

        assertThat(e.lines).anySatisfy { assertThat(it).contains("open interest -3 is negative") }
    }

    @Test
    fun `a series value repeated exactly is dropped as a duplicate`() {
        val series =
            ImportDescriptor(
                kind = IngestKind.SERIES,
                time = listOf("date"),
                fields = mapOf("value" to "v"),
                availableLag = "3d",
            )
        val f = file("cot.csv", "date,v\n2024-01-02,5\n2024-01-02,5\n2024-01-09,6\n")

        val report = IngestFixtures.import(root, listOf(f), "CFTC:X", series, dryRun = true)

        assertThat(report.tally.duplicatesDropped).isEqualTo(1L)
    }
}
