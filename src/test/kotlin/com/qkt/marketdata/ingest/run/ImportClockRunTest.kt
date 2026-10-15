package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A year of MetaTrader hourly bars on a New York close server clock, read on the right and wrong clocks. */
class ImportClockRunTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun brokerYear(header: Boolean): Path {
        val day = DateTimeFormatter.ofPattern("yyyy.MM.dd")
        val rows =
            generateSequence(LocalDate.of(2024, 1, 8)) { it.plusWeeks(1) }.take(52).flatMap { monday ->
                (0 until 5).asSequence().flatMap { d ->
                    (0 until 24).asSequence().map { h ->
                        "${monday.plusDays(
                            d.toLong(),
                        ).format(day)}\t${"%02d".format(h)}:00:00\t1.1\t1.2\t1.0\t1.1\t9\t0\t12"
                    }
                }
            }
        val head = if (header) "<DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>\t<TICKVOL>\t<VOL>\t<SPREAD>\n" else ""
        return dir.resolve("EURUSD_H1.csv").also { Files.writeString(it, head + rows.joinToString("\n") + "\n") }
    }

    @Test
    fun `a year of New York close bars proves the ny-close clock`() {
        val report = IngestFixtures.import(root, listOf(brokerYear(header = true)), "BROKER:EURUSD", dryRun = true)

        assertThat(report.clock).startsWith("ny-close")
        assertThat(report.clockEvidence).isEqualTo("ny-close: 51 weekly opens, 51 at 17:00 New York")
    }

    @Test
    fun `reading them on Europe-Athens is refused and names the clock that fits`() {
        assertThatThrownBy {
            IngestFixtures.import(
                root,
                listOf(brokerYear(header = true)),
                "BROKER:EURUSD",
                ImportDescriptor(clock = "Europe/Athens"),
                dryRun = true,
            )
        }.isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("contradict the clock")
            .hasMessageContaining("Clocks that fit: ny-close")
    }

    @Test
    fun `trust-clock imports on the stated clock anyway`() {
        val report =
            IngestFixtures.import(
                root,
                listOf(brokerYear(header = true)),
                "BROKER:EURUSD",
                ImportDescriptor(clock = "Europe/Athens"),
                dryRun = true,
                trustClock = true,
            )

        assertThat(report.clock).startsWith("Europe/Athens")
    }

    @Test
    fun `with no clock given, an FX file's weekly opens single out ny-close`() {
        val generic =
            ImportDescriptor(
                kind = IngestKind.BARS,
                time = listOf("$0", "$1"),
                timeFormat = "yyyy.MM.dd HH:mm:ss",
                fields = mapOf("open" to "$2", "high" to "$3", "low" to "$4", "close" to "$5"),
            )
        val report =
            IngestFixtures.import(
                root,
                listOf(brokerYear(header = false)),
                "BROKER:EURUSD",
                generic,
                dryRun = true,
            )

        assertThat(report.clock).isEqualTo("ny-close (inferred: ny-close: 51 weekly opens, 51 at 17:00 New York)")
    }

    @Test
    fun `naive times for a 24-7 symbol need a stated clock`() {
        val generic =
            ImportDescriptor(
                kind = IngestKind.BARS,
                time = listOf("$0", "$1"),
                timeFormat = "yyyy.MM.dd HH:mm:ss",
                fields = mapOf("open" to "$2", "high" to "$3", "low" to "$4", "close" to "$5"),
            )

        assertThatThrownBy {
            IngestFixtures.import(root, listOf(brokerYear(header = false)), "BROKER:BTCUSD", generic, dryRun = true)
        }.hasMessageContaining("pass --tz")
    }
}
