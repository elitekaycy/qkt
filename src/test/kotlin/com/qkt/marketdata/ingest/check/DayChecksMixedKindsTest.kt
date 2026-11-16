package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A rate landing on the time of a kept record of another kind is a conflict, never a silent duplicate. */
class DayChecksMixedKindsTest {
    private val day = LocalDate.of(2024, 1, 2)
    private val t0 = 1_704_153_600_000L

    private fun funding(order: Long) = FundingRecord(t0, order, BigDecimal("0.0001"), null)

    @Test
    fun `a rate at the time of a kept tick is dropped as two values at one time`() {
        val tick = TickRecord(t0, 1, BigDecimal("100"), null, null, null, null, null)
        val tally = CheckTally()

        val kept = DayChecks(tally, null).clean(day, listOf(tick, funding(2)))

        assertThat(kept).containsExactly(tick)
        assertThat(tally.duplicatesDropped).isZero()
        assertThat(tally.problems.single()).contains("two different values at the same time")
    }

    @Test
    fun `a rate at the time of a kept figure is kept last when asked`() {
        val figure = SeriesRecord(t0, 1, t0, BigDecimal("5"))
        val tally = CheckTally()

        val kept = DayChecks(tally, null, keepLast = true).clean(day, listOf(figure, funding(2)))

        assertThat(kept).containsExactly(funding(2))
        assertThat(tally.conflictsKeptLast.single()).contains("two different values at the same time")
    }
}
