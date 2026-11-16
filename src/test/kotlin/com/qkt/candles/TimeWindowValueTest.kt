package com.qkt.candles

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [TimeWindow] is a value: equal durations are equal, hash alike and print their duration. */
class TimeWindowValueTest {
    @Test
    fun `a window is a value of its duration`() {
        val a: Any = TimeWindow(1_000L)
        val b: Any = TimeWindow.ONE_SECOND

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(TimeWindow.ONE_MINUTE)
        assertThat(a.toString()).isEqualTo("TimeWindow(durationMs=1000)")
    }
}
