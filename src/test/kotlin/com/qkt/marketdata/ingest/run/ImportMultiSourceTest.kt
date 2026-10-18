package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.store.ImportProvenance
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * One symbol fed by two sources that overlap: "Dukascopy" Monday to Friday, then "MT5" from Wednesday to
 * the next Friday, quoting two points higher. Each day stays whole from one source, the overlap is checked,
 * and the switch between sources is reported.
 */
class ImportMultiSourceTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")
    private val ticks =
        ImportDescriptor(
            kind = IngestKind.TICKS,
            time = listOf("t"),
            fields =
                mapOf(
                    "bid" to "bid",
                    "ask" to "ask",
                ),
        )

    private fun price(minute: Long): BigDecimal =
        BigDecimal("1.10000").add(
            BigDecimal(
                (minute * minute * 7919 + minute * 31) % 997,
            ).movePointLeft(5),
        )

    /** Ticks every minute of [days] (weekdays) from [startHour] UTC, priced [bump] points above the reference. */
    private fun source(
        name: String,
        days: List<LocalDate>,
        bump: Int = 0,
        shiftMs: Long = 0,
        startHour: Int = 0,
    ): Path {
        val rows =
            days.filter { it.dayOfWeek.value <= 5 }.flatMap { day ->
                val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                (startHour * 60 until 22 * 60).map { m ->
                    val t = start + m * 60_000L
                    val bid = price(t / 60_000).add(BigDecimal(bump).movePointLeft(5))
                    "${t + shiftMs},$bid,${bid.add(BigDecimal("0.00010"))}"
                }
            }
        return dir.resolve(name).also { Files.writeString(it, "t,bid,ask\n" + rows.joinToString("\n") + "\n") }
    }

    private fun days(
        from: String,
        to: String,
    ): List<LocalDate> =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }
            .takeWhile {
                it <=
                    LocalDate.parse(to)
            }.toList()

    @Test
    fun `an overlapping second source fills only what the first lacks and reports the seam`() {
        IngestFixtures.import(root, listOf(source("duka.csv", days("2024-03-04", "2024-03-08"))), "EURUSD", ticks)

        // The fixture's Monday opens at 00:00 UTC are unrealistic after the US DST switch; this test is about overlap.
        val mt5 =
            IngestFixtures.import(
                root,
                listOf(source("mt5.csv", days("2024-03-06", "2024-03-15"), bump = 2)),
                "EURUSD",
                ticks,
                trustClock = true,
            )

        assertThat(mt5.outcomes.kept.map { it.toString() }).containsExactly("2024-03-06", "2024-03-07", "2024-03-08")
        assertThat(mt5.outcomes.written.map { it.toString() }).startsWith("2024-03-11").endsWith("2024-03-15")
        assertThat(mt5.outcomes.overlap).contains("offset 0m").contains("close Δ median 2 pt")
        assertThat(mt5.outcomes.seams.single()).startsWith("2024-03-08 → 2024-03-11")
        val provenance = ImportProvenance.read(root.resolve("symbols/EURUSD"))
        assertThat(provenance.days.keys).contains("2024-03-04", "2024-03-15")
    }

    @Test
    fun `a partial first day of the new source never replaces a complete stored day`() {
        IngestFixtures.import(root, listOf(source("duka.csv", days("2024-03-06", "2024-03-06"))), "EURUSD", ticks)

        val partial =
            IngestFixtures.import(
                root,
                listOf(source("mt5.csv", days("2024-03-06", "2024-03-07"), startHour = 14)),
                "EURUSD",
                ticks,
            )

        assertThat(partial.outcomes.kept.map { it.toString() }).containsExactly("2024-03-06")
        assertThat(partial.outcomes.replaced).isEmpty()
    }

    @Test
    fun `a fuller day replaces a partial stored one, keeping the old file as a backup`() {
        IngestFixtures.import(
            root,
            listOf(source("duka.csv", days("2024-03-06", "2024-03-06"), startHour = 14)),
            "EURUSD",
            ticks,
        )

        val fuller =
            IngestFixtures.import(
                root,
                listOf(source("mt5.csv", days("2024-03-06", "2024-03-06"))),
                "EURUSD",
                ticks,
            )

        assertThat(fuller.outcomes.replaced.map { it.toString() }).containsExactly("2024-03-06")
        assertThat(Files.exists(root.resolve("symbols/EURUSD/.import-backup"))).isTrue()
    }

    @Test
    fun `a second source on the wrong clock shows against the stored days and refuses`() {
        IngestFixtures.import(root, listOf(source("duka.csv", days("2024-03-04", "2024-03-08"))), "EURUSD", ticks)
        // Inside one week: no weekly open to judge, so the comparison with the stored days is what catches the clock.
        val shifted = source("mt5.csv", days("2024-03-06", "2024-03-08"), shiftMs = 7_200_000)
        val before = Files.readAllBytes(root.resolve("symbols/EURUSD/2024-03-08.csv.gz"))

        assertThatThrownBy { IngestFixtures.import(root, listOf(shifted), "EURUSD", ticks) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("shifted by")
            .hasMessageContaining("wrong clock")
        assertThat(Files.readAllBytes(root.resolve("symbols/EURUSD/2024-03-08.csv.gz"))).isEqualTo(before)
    }

    @Test
    fun `a hole between two sources is named at the seam`() {
        IngestFixtures.import(root, listOf(source("duka.csv", days("2024-03-04", "2024-03-05"))), "EURUSD", ticks)

        val mt5 =
            IngestFixtures.import(
                root,
                listOf(source("mt5.csv", days("2024-03-07", "2024-03-08"))),
                "EURUSD",
                ticks,
            )

        assertThat(
            mt5.outcomes.seams.single(),
        ).startsWith("2024-03-05 → 2024-03-07").endsWith("1 weekday(s) with no data between")
    }

    @Test
    fun `replace and skip still mean exactly that`() {
        IngestFixtures.import(root, listOf(source("duka.csv", days("2024-03-04", "2024-03-08"))), "EURUSD", ticks)
        val mt5 = source("mt5.csv", days("2024-03-06", "2024-03-08"), bump = 2)

        val skipped = IngestFixtures.import(root, listOf(mt5), "EURUSD", ticks, onOverlap = OverlapPolicy.SKIP)
        assertThat(skipped.outcomes.skipped).hasSize(3)
        val replaced = IngestFixtures.import(root, listOf(mt5), "EURUSD", ticks, onOverlap = OverlapPolicy.REPLACE)
        assertThat(replaced.outcomes.replaced).hasSize(3)
        assertThat(replaced.outcomes.seams.single()).startsWith("2024-03-05 → 2024-03-06")
    }
}
