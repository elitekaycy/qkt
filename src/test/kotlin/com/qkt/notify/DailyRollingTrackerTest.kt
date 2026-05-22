package com.qkt.notify

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyRollingTrackerTest {
    @Test
    fun `snapshot reports the trades and halts recorded for a strategy`() {
        val tracker = DailyRollingTracker()
        tracker.recordTrade("alpha")
        tracker.recordTrade("alpha")
        tracker.recordHalt("alpha")

        val totals = tracker.snapshot("alpha", BigDecimal("1000"))

        assertThat(totals.tradesToday).isEqualTo(2)
        assertThat(totals.haltsToday).isEqualTo(1)
    }

    @Test
    fun `snapshot resets the window so the next snapshot starts from zero`() {
        val tracker = DailyRollingTracker()
        tracker.recordTrade("alpha")
        tracker.recordHalt("alpha")
        tracker.snapshot("alpha", BigDecimal("1000"))

        val second = tracker.snapshot("alpha", BigDecimal("1000"))

        assertThat(second.tradesToday).isEqualTo(0)
        assertThat(second.haltsToday).isEqualTo(0)
    }

    @Test
    fun `counts are tracked per strategy`() {
        val tracker = DailyRollingTracker()
        tracker.recordTrade("alpha")
        tracker.recordTrade("beta")
        tracker.recordTrade("beta")

        assertThat(tracker.snapshot("alpha", BigDecimal("1000")).tradesToday).isEqualTo(1)
        assertThat(tracker.snapshot("beta", BigDecimal("1000")).tradesToday).isEqualTo(2)
    }

    @Test
    fun `first snapshot has no equity baseline and reports zero delta`() {
        val tracker = DailyRollingTracker()

        val totals = tracker.snapshot("alpha", BigDecimal("1000"))

        assertThat(totals.equityDeltaPct).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `equityDeltaPct is the percent change from the previous snapshot`() {
        val tracker = DailyRollingTracker()
        tracker.snapshot("alpha", BigDecimal("1000")) // baseline

        val totals = tracker.snapshot("alpha", BigDecimal("1100"))

        // (1100 - 1000) / 1000 * 100 = 10%
        assertThat(totals.equityDeltaPct).isEqualByComparingTo(BigDecimal("10"))
    }

    @Test
    fun `equityDeltaPct is zero when the previous baseline was zero`() {
        val tracker = DailyRollingTracker()
        tracker.snapshot("alpha", BigDecimal.ZERO) // baseline of zero

        val totals = tracker.snapshot("alpha", BigDecimal("500"))

        assertThat(totals.equityDeltaPct).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
