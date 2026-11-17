package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.InputLine
import com.qkt.marketdata.ingest.clock.ClockEvidence
import com.qkt.marketdata.ingest.clock.ImportClock
import com.qkt.marketdata.ingest.parse.ParsedTime
import com.qkt.marketdata.ingest.parse.TimeFormat
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.record.DayBuckets
import com.qkt.marketdata.ingest.record.RecordCodec
import com.qkt.marketdata.ingest.record.RowFaults
import com.qkt.marketdata.ingest.record.RowTime
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.CsvRow
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Small import internals whose edge arms the end-to-end import tests never reach. */
class ImportInternalsCoverageTest {
    @TempDir
    lateinit var dir: Path

    private val lineBits = InputPass.LINE_BITS

    @Test
    fun `a newest-first input reverses its line order in the tie-break key`() {
        val read = ReadResult(DayBuckets(dir), emptyList(), null, setOf(1))
        val first = (1L shl lineBits) or 3L
        val second = (1L shl lineBits) or 4L

        assertThat(read.orderKey(first)).isGreaterThan(read.orderKey(second))
        assertThat(read.orderKey(first) ushr lineBits).isEqualTo(1L)
        assertThat(read.orderKey(2L shl lineBits or 5L)).isEqualTo(2L shl lineBits or 5L)
    }

    @Test
    fun `validation without a keep-last flag reports no paired quotes for ticks`() {
        val read = ReadResult(DayBuckets(dir), emptyList(), null, emptySet())

        val stage = ValidateStage(read, IngestKind.TICKS, null)

        assertThat(stage.pairedQuotes).isFalse()
        assertThat(stage.timeframe).isNull()
    }

    @Test
    fun `an unknown scratch record kind is refused when decoding`() {
        assertThatThrownBy { RecordCodec.decode("X,1,2") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unknown scratch record")
    }

    @Test
    fun `row faults are empty until one is added`() {
        val faults = RowFaults()
        assertThat(faults.isEmpty).isTrue()

        faults.add(InputLine("f.csv", 2, "x"), "bad")

        assertThat(faults.isEmpty).isFalse()
    }

    @Test
    fun `reading a day without an order key keeps input order for equal times`() {
        val buckets = DayBuckets(dir)
        val tick = { order: Long ->
            com.qkt.marketdata.ingest.record
                .TickRecord(1_000L, order, BigDecimal.ONE, null, null, null, null, null)
        }
        buckets.add(tick(2L))
        buckets.add(tick(1L))

        val day = buckets.day(LocalDate.of(1970, 1, 1))

        assertThat(day.map { it.order }).containsExactly(1L, 2L)
    }

    @Test
    fun `clock evidence leaves out a wall time in a DST gap and a calendar date`() {
        val gap = ParsedTime.Local(LocalDateTime.of(2026, 3, 8, 2, 30))
        val date = ParsedTime.Date(LocalDate.of(2026, 3, 9))

        val evidence = ClockEvidence.of(ImportClock.Region(ZoneId.of("America/New_York")), listOf(gap, date))

        assertThat(evidence.weeks).isEmpty()
    }

    @Test
    fun `a wall-clock time with no clock given is refused naming the missing tz`() {
        val time = RowTime(listOf(CellRef.Index(0)), TimeFormat.Iso, null)
        val row = CsvRow(InputLine("t.csv", 1, "2026-01-05T10:00:00"), listOf("2026-01-05T10:00:00"))

        assertThatThrownBy { time.epochMs(row) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no clock was given")
    }
}
