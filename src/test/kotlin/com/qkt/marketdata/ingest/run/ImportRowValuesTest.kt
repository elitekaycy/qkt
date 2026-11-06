package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import com.qkt.marketdata.ingest.record.BarRecord
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** How row values become records, and the rows and mappings an import refuses. */
class ImportRowValuesTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun file(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    private val askBars =
        ImportDescriptor(
            kind = IngestKind.BARS,
            time = listOf("$0"),
            basis = PriceBasis.ASK,
            fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4", "spread" to "$5"),
        )
    private val twoBars =
        "2024-01-02T10:00:00Z,1.1002,1.1004,1.1000,1.1002,20\n" +
            "2024-01-02T10:01:00Z,1.1,1.1,1.1,1.1,20\n"

    @Test
    fun `ask bars move down to mid by half of each bar's spread`() {
        val input = listOf(file("ask.csv", twoBars))
        val report = IngestFixtures.import(root, input, "DATA:EURUSD", askBars.copy(digits = 5), dryRun = true)

        val first =
            report.preview
                .map { it.second }
                .filterIsInstance<BarRecord>()
                .first()
        assertThat(first.close).isEqualByComparingTo("1.1001")
        assertThat(first.high).isEqualByComparingTo("1.1003")
        assertThat(report.basis).contains("ask → mid")
    }

    @Test
    fun `a spread without the instrument's digits is refused`() {
        assertThatThrownBy {
            IngestFixtures.import(root, listOf(file("ask.csv", twoBars)), "DATA:ZZZQQQ", askBars, dryRun = true)
        }.isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("a spread needs the instrument's digits (--digits)")
    }

    @Test
    fun `a tick row with no price and no quote is a row error`() {
        val ticks = ImportDescriptor(kind = IngestKind.TICKS, time = listOf("$0"), fields = mapOf("price" to "$1"))
        val f = file("t.csv", "2024-01-02T10:00:00Z,1.1\n2024-01-02T10:00:01Z,\n2024-01-02T10:00:02Z,1.2\n")

        assertThatThrownBy { IngestFixtures.import(root, listOf(f), "DATA:EURUSD", ticks, dryRun = true) }
            .hasMessageContaining("no price, bid or ask")
    }

    @Test
    fun `a mapping without a time column is refused`() {
        val ticks = ImportDescriptor(kind = IngestKind.TICKS, fields = mapOf("price" to "$1"))
        val f = file("t.csv", "2024-01-02T10:00:00Z,1.1\n2024-01-02T10:00:01Z,1.2\n")

        assertThatThrownBy { IngestFixtures.import(root, listOf(f), "DATA:EURUSD", ticks, dryRun = true) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("--map time=")
    }

    @Test
    fun `a file no preset fits imported without a kind is refused`() {
        val f = file("mystery.csv", "alpha,beta\n1,2\n3,4\n")

        assertThatThrownBy { IngestFixtures.import(root, listOf(f), "DATA:EURUSD", ImportDescriptor(), dryRun = true) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("no preset fits and no --kind was given")
    }

    @Test
    fun `values said to be float32 that are not are kept as written and counted`() {
        val bars =
            ImportDescriptor(
                kind = IngestKind.BARS,
                time = listOf("$0"),
                float32 = true,
                fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4"),
            )
        val rows =
            "2024-01-02T10:00:00Z,1234567.89,1234567.89,1234567.89,1234567.89\n" +
                "2024-01-02T10:01:00Z,1234567.89,1234567.89,1234567.89,1234567.89\n"

        val report = IngestFixtures.import(root, listOf(file("f.csv", rows)), "DATA:EURUSD", bars, dryRun = true)

        assertThat(report.warnings).anySatisfy { assertThat(it).contains("were not float32 values") }
        val first =
            report.preview
                .map { it.second }
                .filterIsInstance<BarRecord>()
                .first()
        assertThat(first.close).isEqualByComparingTo("1234567.89")
    }
}
