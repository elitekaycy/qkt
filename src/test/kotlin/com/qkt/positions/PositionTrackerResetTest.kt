package com.qkt.positions

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionTrackerResetTest {
    @Test
    fun `reset overwrites existing position with new qty and avgPx`() {
        val tracker = PositionTracker()
        tracker.reset("BTCUSDT", BigDecimal("0.5"), BigDecimal("80000"))

        val position = tracker.positionFor("BTCUSDT")

        assertThat(position?.quantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(position?.avgEntryPrice).isEqualByComparingTo(BigDecimal("80000"))
    }

    @Test
    fun `reset to zero quantity removes the entry`() {
        val tracker = PositionTracker()
        tracker.reset("BTCUSDT", BigDecimal("0.5"), BigDecimal("80000"))
        tracker.reset("BTCUSDT", BigDecimal.ZERO, BigDecimal.ZERO)

        assertThat(tracker.positionFor("BTCUSDT")).isNull()
    }

    @Test
    fun `reset accepts negative quantity for short positions`() {
        val tracker = PositionTracker()
        tracker.reset("BTCUSDT", BigDecimal("-0.5"), BigDecimal("80000"))

        assertThat(tracker.positionFor("BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("-0.5"))
    }
}
