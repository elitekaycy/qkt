package com.qkt.candles

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeWindowTest {
    @Test
    fun `windowStartFor rounds down to nearest multiple of durationMs`() {
        val window = TimeWindow(60_000L)
        assertThat(window.windowStartFor(0L)).isEqualTo(0L)
        assertThat(window.windowStartFor(23_456L)).isEqualTo(0L)
        assertThat(window.windowStartFor(59_999L)).isEqualTo(0L)
        assertThat(window.windowStartFor(60_000L)).isEqualTo(60_000L)
        assertThat(window.windowStartFor(60_001L)).isEqualTo(60_000L)
        assertThat(window.windowStartFor(125_678L)).isEqualTo(120_000L)
    }

    @Test
    fun `windowEndFor returns windowStartFor plus durationMs`() {
        val window = TimeWindow(60_000L)
        assertThat(window.windowEndFor(23_456L)).isEqualTo(60_000L)
        assertThat(window.windowEndFor(60_000L)).isEqualTo(120_000L)
        assertThat(window.windowEndFor(125_678L)).isEqualTo(180_000L)
    }

    @Test
    fun `windowStartFor is idempotent on a boundary timestamp`() {
        val window = TimeWindow(60_000L)
        val boundary = 60_000L
        assertThat(window.windowStartFor(boundary)).isEqualTo(boundary)
        assertThat(window.windowStartFor(window.windowStartFor(boundary))).isEqualTo(boundary)
    }

    @Test
    fun `throws on zero or negative durationMs`() {
        assertThatThrownBy { TimeWindow(0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TimeWindow(-1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `named constants have expected duration values`() {
        assertThat(TimeWindow.ONE_SECOND.durationMs).isEqualTo(1_000L)
        assertThat(TimeWindow.ONE_MINUTE.durationMs).isEqualTo(60_000L)
        assertThat(TimeWindow.FIVE_MINUTES.durationMs).isEqualTo(300_000L)
        assertThat(TimeWindow.FIFTEEN_MINUTES.durationMs).isEqualTo(900_000L)
        assertThat(TimeWindow.ONE_HOUR.durationMs).isEqualTo(3_600_000L)
    }
}
