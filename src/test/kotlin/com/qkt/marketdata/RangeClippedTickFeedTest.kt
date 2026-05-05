package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RangeClippedTickFeedTest {
    private fun tick(ts: Long) = Tick("X", Money.of("1"), ts)

    @Test
    fun `drops ticks before from`() {
        val inner = HistoricalTickFeed(listOf(tick(1L), tick(5L), tick(10L)))
        val clipped = RangeClippedTickFeed(inner, fromMs = 5L, toMs = 100L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(5L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(10L)
        assertThat(clipped.next()).isNull()
    }

    @Test
    fun `stops at first tick at or after to`() {
        val inner = HistoricalTickFeed(listOf(tick(1L), tick(5L), tick(10L)))
        val clipped = RangeClippedTickFeed(inner, fromMs = 0L, toMs = 10L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(1L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(5L)
        assertThat(clipped.next()).isNull()
    }

    @Test
    fun `closes inner feed early when to reached`() {
        var closed = false
        val inner =
            object : TickFeed {
                private val src = HistoricalTickFeed(listOf(tick(1L), tick(10L)))

                override fun next(): Tick? = src.next()

                override fun close() {
                    closed = true
                }
            }
        val clipped = RangeClippedTickFeed(inner, fromMs = 0L, toMs = 5L)
        clipped.next()
        clipped.next()
        assertThat(closed).isTrue()
    }

    @Test
    fun `passthrough when range covers all`() {
        val inner = HistoricalTickFeed(listOf(tick(1L), tick(2L)))
        val clipped = RangeClippedTickFeed(inner, fromMs = 0L, toMs = 100L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(1L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(2L)
        assertThat(clipped.next()).isNull()
    }
}
