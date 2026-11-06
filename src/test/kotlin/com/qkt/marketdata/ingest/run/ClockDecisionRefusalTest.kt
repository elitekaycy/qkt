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

/** The clock refusals an import gives when its timestamps cannot settle the clock. */
class ClockDecisionRefusalTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private val generic =
        ImportDescriptor(
            kind = IngestKind.BARS,
            time = listOf("$0"),
            timeFormat = "yyyy-MM-dd HH:mm",
            fields = mapOf("open" to "$1", "high" to "$2", "low" to "$3", "close" to "$4"),
        )

    /** Hourly bars on Friday 2024-01-12 up to [fridayLast], then from Monday 2024-01-15 at [mondayFirst]. */
    private fun weekend(
        fridayLast: Int,
        mondayFirst: Int,
    ): Path {
        val friday = (fridayLast - 3..fridayLast).map { "2024-01-12 %02d:00".format(it) }
        val monday = (mondayFirst..mondayFirst + 3).map { "2024-01-15 %02d:00".format(it) }
        val rows = (friday + monday).joinToString("\n") { "$it,1.1,1.2,1.0,1.1" }
        return dir.resolve("bars.csv").also { Files.writeString(it, rows + "\n") }
    }

    @Test
    fun `a stated clock for timestamps that carry their own zone is refused`() {
        val file = dir.resolve("zoned.csv")
        Files.writeString(file, "2024-01-15T00:00:00Z,1.1,1.2,1.0,1.1\n2024-01-15T01:00:00Z,1.1,1.2,1.0,1.1\n")
        val zoned = generic.copy(timeFormat = null, clock = "UTC")

        assertThatThrownBy { IngestFixtures.import(root, listOf(file), "BROKER:EURUSD", zoned, dryRun = true) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("carry their own zone")
            .hasMessageContaining("drop it")
    }

    @Test
    fun `an FX week that opens where no market opens is refused saying no candidate fits`() {
        val file = weekend(fridayLast = 12, mondayFirst = 12)

        assertThatThrownBy { IngestFixtures.import(root, listOf(file), "BROKER:EURUSD", generic, dryRun = true) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("do not single out one clock")
            .hasMessageContaining("No candidate fits.")
    }

    @Test
    fun `a single metals week several clocks fit differently is refused naming them`() {
        val file = weekend(fridayLast = 23, mondayFirst = 0)

        val refusal =
            catchRefusal { IngestFixtures.import(root, listOf(file), "BROKER:XAUUSD", generic, dryRun = true) }

        assertThat(refusal.message).contains("Fitting: ").contains("ny-close").contains("Europe/Berlin")
        assertThat(refusal.lines).hasSize(9)
        assertThat(refusal.lines.filter { it.endsWith(" ✓") }).hasSizeGreaterThan(1)
    }

    private fun catchRefusal(block: () -> Unit): ImportRefusal =
        try {
            block()
            throw AssertionError("expected a refusal")
        } catch (e: ImportRefusal) {
            e
        }
}
