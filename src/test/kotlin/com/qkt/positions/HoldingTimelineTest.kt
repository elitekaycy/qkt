package com.qkt.positions

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What was held just before a moment, from the changes kept. */
class HoldingTimelineTest {
    private val now = { BigDecimal("9") }

    @Test
    fun `a change at or after the moment is not held then, one before it is`() {
        val timeline = HoldingTimeline<String>(keepMs = 1_000)
        timeline.record("a", 100, BigDecimal.ZERO, BigDecimal("2"))
        timeline.record("a", 200, BigDecimal("2"), BigDecimal("-1"))

        assertThat(timeline.heldAt("a", 50, now)).isEqualByComparingTo("0")
        assertThat(timeline.heldAt("a", 200, now)).isEqualByComparingTo("2")
        assertThat(timeline.heldAt("a", 201, now)).isEqualByComparingTo("-1")
        assertThat(timeline.heldAt("b", 201, now)).isEqualByComparingTo("9")
    }

    @Test
    fun `a dropped change leaves the next one's quantity before it, so a kept moment is still told right`() {
        val timeline = HoldingTimeline<String>(keepMs = 1_000)
        timeline.record("a", 100, BigDecimal.ZERO, BigDecimal("2"))
        timeline.record("a", 900, BigDecimal("2"), BigDecimal("3"))
        timeline.record("a", 1_500, BigDecimal("3"), BigDecimal("1"))

        assertThat(timeline.heldAt("a", 950, now)).isEqualByComparingTo("3")
        assertThat(timeline.heldAt("a", 600, now)).isEqualByComparingTo("2")
    }
}
