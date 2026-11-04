package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MergingTickFeedTest {
    private fun feed(ticks: List<Tick>): TickFeed = HistoricalTickFeed(ticks)

    private fun tick(
        symbol: String,
        ts: Long,
        price: String = "1",
    ) = Tick(symbol, Money.of(price), ts)

    @Test
    fun `interleaves two feeds in timestamp order`() {
        val a = feed(listOf(tick("A", 1L), tick("A", 3L), tick("A", 5L)))
        val b = feed(listOf(tick("B", 2L), tick("B", 4L)))
        val m = MergingTickFeed(listOf(a, b))
        val collected = generateSequence { m.next() }.toList().map { it.timestamp }
        assertThat(collected).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `ticks that carry their live arrival merge in arrival order, each feed keeping its own order`() {
        // EXNESS stamps its tick first but it reaches qkt last; PAPER's two ticks arrive before it.
        val exness = feed(listOf(Tick("EXNESS:X", Money.of("1"), 10L, receivedMs = 900L)))
        val paper =
            feed(
                listOf(
                    Tick("PAPER:X", Money.of("1"), 50L, receivedMs = 100L),
                    Tick("PAPER:X", Money.of("1"), 60L, receivedMs = 950L),
                ),
            )
        val m = MergingTickFeed(listOf(exness, paper))
        val collected = generateSequence { m.next() }.toList().map { it.symbol to it.timestamp }
        assertThat(collected).containsExactly("PAPER:X" to 50L, "EXNESS:X" to 10L, "PAPER:X" to 60L)
    }

    @Test
    fun `tie break by feed list order`() {
        val a = feed(listOf(tick("A", 5L)))
        val b = feed(listOf(tick("B", 5L)))
        val m = MergingTickFeed(listOf(a, b))
        val first = m.next()!!
        val second = m.next()!!
        assertThat(first.symbol).isEqualTo("A")
        assertThat(second.symbol).isEqualTo("B")
        assertThat(m.next()).isNull()
    }

    @Test
    fun `handles three feeds with mixed densities`() {
        val a = feed(listOf(tick("A", 1L), tick("A", 7L)))
        val b = feed(listOf(tick("B", 2L), tick("B", 3L), tick("B", 4L), tick("B", 5L)))
        val c = feed(listOf(tick("C", 6L)))
        val m = MergingTickFeed(listOf(a, b, c))
        val ts = generateSequence { m.next() }.toList().map { it.timestamp }
        assertThat(ts).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `empty inner feed produces no ticks`() {
        val m = MergingTickFeed(listOf(feed(emptyList()), feed(emptyList())))
        assertThat(m.next()).isNull()
    }

    @Test
    fun `single feed merge degenerates to passthrough`() {
        val a = feed(listOf(tick("A", 1L), tick("A", 2L)))
        val m = MergingTickFeed(listOf(a))
        assertThat(m.next()!!.timestamp).isEqualTo(1L)
        assertThat(m.next()!!.timestamp).isEqualTo(2L)
        assertThat(m.next()).isNull()
    }

    @Test
    fun `close propagates to all inner feeds`() {
        var closedA = false
        var closedB = false
        val a =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() {
                    closedA = true
                }
            }
        val b =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() {
                    closedB = true
                }
            }
        MergingTickFeed(listOf(a, b)).close()
        assertThat(closedA).isTrue()
        assertThat(closedB).isTrue()
    }
}
