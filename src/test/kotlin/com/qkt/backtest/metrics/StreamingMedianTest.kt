package com.qkt.backtest.metrics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamingMedianTest {
    @Test
    fun `null before any value`() {
        assertThat(StreamingMedian().estimate()).isNull()
    }

    @Test
    fun `exact median for fewer than five values`() {
        val m = StreamingMedian()
        listOf(5.0, 1.0, 3.0).forEach(m::accept)
        // Under the marker count the estimate is the exact small-sample median.
        assertThat(m.estimate()).isEqualTo(3.0)
        assertThat(m.count).isEqualTo(3)
    }

    @Test
    fun `even small sample averages the two middle values`() {
        val m = StreamingMedian()
        listOf(4.0, 2.0).forEach(m::accept)
        assertThat(m.estimate()).isEqualTo(3.0)
    }

    @Test
    fun `converges near the true median of a uniform ramp`() {
        val m = StreamingMedian()
        // 1..1001: true median is 501.
        for (i in 1..1001) m.accept(i.toDouble())
        assertThat(m.estimate()!!).isCloseTo(
            501.0,
            org.assertj.core.data.Offset
                .offset(20.0),
        )
    }

    @Test
    fun `is deterministic for the same input order`() {
        fun run(): Double {
            val m = StreamingMedian()
            for (i in 1..500) m.accept((i * 7 % 53).toDouble())
            return m.estimate()!!
        }
        assertThat(run()).isEqualTo(run())
    }
}
