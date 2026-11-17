package com.qkt.cli

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRebalanceTest {
    @Test
    fun `a rebalance interval is the count of bars it spans, rounded up`() {
        val hourBars = TimeWindow.parse("1h")

        assertThat(rebalanceBars(86_400_000L, hourBars)).isEqualTo(24)
        assertThat(rebalanceBars(5_400_000L, hourBars)).isEqualTo(2)
    }

    @Test
    fun `without an interval or a bar window there is no bar count`() {
        assertThat(rebalanceBars(null, TimeWindow.parse("1h"))).isZero()
        assertThat(rebalanceBars(86_400_000L, null)).isZero()
    }
}
