package com.qkt.marketdata.source

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** After its last bar, a bar feed replays the real ticks the store recorded later, then ends. */
class BarTickFeedTailTest {
    private val bar =
        Candle("X", Money.of("100"), Money.of("101"), Money.of("99"), Money.of("100.5"), Money.of("1"), 0L, 60_000L)

    private fun drain(feed: BarTickFeed): List<Tick> = generateSequence { feed.next() }.toList()

    @Test
    fun `the recorded ticks after the last bar follow its four synthetic ticks`() {
        var asked: Long? = null
        val later = listOf(Tick("X", Money.of("100.4"), 61_017L), Tick("X", Money.of("100.3"), 75_000L))
        val feed =
            BarTickFeed(sequenceOf(bar), tail = { after ->
                asked = after
                later.asSequence()
            })

        val ticks = drain(feed)

        assertThat(asked).isEqualTo(60_000L)
        assertThat(ticks.map { it.timestamp }).containsExactly(0L, 15_000L, 30_000L, 59_999L, 61_017L, 75_000L)
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `with nothing recorded later the feed ends with the last bar`() {
        assertThat(drain(BarTickFeed(sequenceOf(bar)))).hasSize(4)
    }

    @Test
    fun `an empty bar sequence asks for no tail`() {
        var asked = false
        val feed =
            BarTickFeed(emptySequence(), tail = {
                asked = true
                emptySequence()
            })

        assertThat(feed.next()).isNull()
        assertThat(asked).isFalse()
    }
}
