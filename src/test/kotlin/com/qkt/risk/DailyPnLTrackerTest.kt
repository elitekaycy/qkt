package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.common.Money
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyPnLTrackerTest {
    private val day1 = Instant.parse("2024-01-15T10:00:00Z").toEpochMilli()
    private val day2 = Instant.parse("2024-01-16T10:00:00Z").toEpochMilli()

    @Test
    fun `realizedToday accumulates within the same UTC day`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("A", BigDecimal("100"))
        tracker.recordRealized("A", BigDecimal("-30"))

        assertThat(tracker.realizedToday("A")).isEqualByComparingTo(BigDecimal("70"))
    }

    @Test
    fun `realizedToday resets when UTC day changes`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("A", BigDecimal("100"))

        clock.time = day2
        assertThat(tracker.realizedToday("A")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `globalRealizedToday sums across strategies`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("A", BigDecimal("100"))
        tracker.recordRealized("B", BigDecimal("-50"))

        assertThat(tracker.globalRealizedToday()).isEqualByComparingTo(BigDecimal("50"))
    }

    @Test
    fun `blank strategyId still increments global but not per-strategy`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("", BigDecimal("100"))

        assertThat(tracker.globalRealizedToday()).isEqualByComparingTo(BigDecimal("100"))
        assertThat(tracker.realizedToday("")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `a month tracker accumulates across days and resets on the 1st of the UTC month (#855)`() {
        val clock = FixedClock(time = Instant.parse("2024-01-03T10:00:00Z").toEpochMilli())
        val month = DailyPnLTracker(clock, PnLPeriod.UTC_MONTH)
        month.recordRealized("A", BigDecimal("-100"))
        clock.time = Instant.parse("2024-01-31T23:59:59Z").toEpochMilli()
        month.recordRealized("A", BigDecimal("-50"))
        assertThat(month.realizedToday("A")).isEqualByComparingTo("-150")
        clock.time = Instant.parse("2024-02-01T00:00:00Z").toEpochMilli()
        assertThat(month.realizedToday("A")).isEqualByComparingTo(Money.ZERO)
        assertThat(month.globalRealizedToday()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `month keys roll exactly at UTC month boundaries, including year ends and leap days`() {
        fun key(iso: String) = PnLPeriod.UTC_MONTH.keyOf(Instant.parse(iso).toEpochMilli())
        assertThat(key("1970-01-01T00:00:00Z")).isEqualTo(0L)
        assertThat(key("2024-02-29T23:59:59.999Z")).isEqualTo(key("2024-02-01T00:00:00Z"))
        assertThat(key("2024-03-01T00:00:00Z")).isEqualTo(key("2024-02-29T23:59:59.999Z") + 1)
        assertThat(key("2025-01-01T00:00:00Z")).isEqualTo(key("2024-12-31T23:59:59.999Z") + 1)
        assertThat(key("1969-12-31T23:59:59Z")).isEqualTo(-1L)
    }

    @Test
    fun `a month snapshot from a past month is discarded on restore`() {
        val clock = FixedClock(time = Instant.parse("2024-01-20T10:00:00Z").toEpochMilli())
        val first = DailyPnLTracker(clock, PnLPeriod.UTC_MONTH)
        first.recordRealized("A", BigDecimal("-200"))
        val snap = first.snapshot()

        val sameMonth = DailyPnLTracker(clock, PnLPeriod.UTC_MONTH)
        sameMonth.restore(snap)
        assertThat(sameMonth.realizedToday("A")).isEqualByComparingTo("-200")

        clock.time = Instant.parse("2024-02-02T10:00:00Z").toEpochMilli()
        val nextMonth = DailyPnLTracker(clock, PnLPeriod.UTC_MONTH)
        nextMonth.restore(snap)
        assertThat(nextMonth.realizedToday("A")).isEqualByComparingTo(Money.ZERO)
    }
}
