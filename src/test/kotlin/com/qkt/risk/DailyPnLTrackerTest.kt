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
}
