package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.OpenInterestRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DayChecksTest {
    private val day = LocalDate.of(2024, 1, 2)
    private val t0 = 1_704_153_600_000L

    private fun bar(
        t: Long,
        o: String,
        h: String,
        l: String,
        c: String,
        order: Long = t,
    ) = BarRecord(t, order, BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c), BigDecimal.ONE)

    @Test
    fun `an exact duplicate bar is dropped and counted`() {
        val tally = CheckTally()
        val kept =
            DayChecks(
                tally,
                60_000,
            ).clean(day, listOf(bar(t0, "1", "2", "1", "2", 1), bar(t0, "1", "2", "1", "2", 2)))

        assertThat(kept).hasSize(1)
        assertThat(tally.duplicatesDropped).isEqualTo(1)
        assertThat(tally.problems).isEmpty()
    }

    @Test
    fun `two different bars at one time are a problem, not a choice`() {
        val tally = CheckTally()
        DayChecks(tally, 60_000).clean(day, listOf(bar(t0, "1", "2", "1", "2", 1), bar(t0, "1", "3", "1", "2", 2)))

        assertThat(tally.problems.single()).contains("two different bars")
    }

    @Test
    fun `a bar whose high is below its close is a problem`() {
        val tally = CheckTally()
        DayChecks(tally, 60_000).clean(day, listOf(bar(t0, "1", "1.5", "0.9", "2")))

        assertThat(tally.problems.single()).contains("not a valid bar")
    }

    @Test
    fun `a bar off the UTC grid is a problem that points at the clock or label`() {
        val tally = CheckTally()
        DayChecks(tally, 14_400_000).clean(day, listOf(bar(t0 + 79_200_000, "1", "2", "1", "2")))

        assertThat(tally.problems.single()).contains("not on the UTC 240-minute grid")
    }

    @Test
    fun `a crossed quote is dropped as qkt's reader drops it, identical ticks are kept`() {
        val tally = CheckTally()
        val q = TickRecord(t0, 1, null, null, BigDecimal("1.1"), BigDecimal("1.2"), null, null)
        val crossed = q.copy(order = 2, bid = BigDecimal("1.3"))
        val kept = DayChecks(tally).clean(day, listOf(q, q.copy(order = 3), crossed))

        assertThat(kept).hasSize(2)
        assertThat(tally.crossedDropped).isEqualTo(1)
    }

    @Test
    fun `a non-positive price is a problem`() {
        val tally = CheckTally()
        DayChecks(tally).clean(day, listOf(TickRecord(t0, 1, BigDecimal.ZERO, null, null, null, null, null)))

        assertThat(tally.problems.single()).contains("not positive")
    }

    @Test
    fun `two funding rates at one time must agree`() {
        val tally = CheckTally()
        val r = FundingRecord(t0, 1, BigDecimal("0.0001"), null)
        DayChecks(tally).clean(day, listOf(r, r.copy(order = 2, rate = BigDecimal("0.0002"))))

        assertThat(tally.problems.single()).contains("two different values")
    }

    @Test
    fun `two published values usable on one day keep the later report`() {
        val tally = CheckTally()
        val older = SeriesRecord(t0, 1, t0 - 6 * 86_400_000L, BigDecimal("10"))
        val newer = SeriesRecord(t0, 2, t0 - 86_400_000L, BigDecimal("11"))
        val kept = DayChecks(tally).clean(day, listOf(older, newer))

        assertThat((kept.single() as SeriesRecord).value).isEqualByComparingTo("11")
        assertThat(tally.superseded).isEqualTo(1)
    }

    @Test
    fun `on-conflict last keeps the later file's value and lists the time`() {
        val tally = CheckTally()
        val r = FundingRecord(t0, 1, BigDecimal("0.0001"), null)
        val kept =
            DayChecks(
                tally,
                keepLast = true,
            ).clean(day, listOf(r, r.copy(order = 2, rate = BigDecimal("0.0002"))))

        assertThat((kept.single() as FundingRecord).rate).isEqualByComparingTo("0.0002")
        assertThat(tally.problems).isEmpty()
        assertThat(tally.conflictsKeptLast).hasSize(1)
    }

    @Test
    fun `an open interest of exactly zero is a venue placeholder, dropped and counted`() {
        val tally = CheckTally()
        val zero = OpenInterestRecord(t0, 1, BigDecimal.ZERO)
        val kept =
            DayChecks(
                tally,
            ).clean(day, listOf(zero, zero.copy(timeMs = t0 + 300_000, openInterest = BigDecimal("39080.231"))))

        assertThat(kept).hasSize(1)
        assertThat(tally.zeroOpenInterestDropped).isEqualTo(1)
    }
}
