package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConcatenatedTickFeedTest {
    private fun tick(ts: Long) = Tick("X", Money.of("1"), ts)

    @Test
    fun `streams feeds end to end`() {
        val a = HistoricalTickFeed(listOf(tick(1L), tick(2L)))
        val b = HistoricalTickFeed(listOf(tick(3L)))
        val cat = ConcatenatedTickFeed(listOf(a, b))
        val ts = generateSequence { cat.next() }.toList().map { it.timestamp }
        assertThat(ts).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `empty feed list returns null immediately`() {
        val cat = ConcatenatedTickFeed(emptyList())
        assertThat(cat.next()).isNull()
    }

    @Test
    fun `closes earlier feeds as it advances`() {
        var closedA = false
        var closedB = false
        val a =
            object : TickFeed {
                private var emitted = false

                override fun next(): Tick? =
                    if (emitted) {
                        null
                    } else {
                        emitted = true
                        tick(1L)
                    }

                override fun close() {
                    closedA = true
                }
            }
        val b =
            object : TickFeed {
                private var emitted = false

                override fun next(): Tick? =
                    if (emitted) {
                        null
                    } else {
                        emitted = true
                        tick(2L)
                    }

                override fun close() {
                    closedB = true
                }
            }
        val cat = ConcatenatedTickFeed(listOf(a, b))
        assertThat(cat.next()!!.timestamp).isEqualTo(1L)
        assertThat(cat.next()!!.timestamp).isEqualTo(2L)
        assertThat(cat.next()).isNull()
        assertThat(closedA).isTrue()
        assertThat(closedB).isTrue()
    }
}
