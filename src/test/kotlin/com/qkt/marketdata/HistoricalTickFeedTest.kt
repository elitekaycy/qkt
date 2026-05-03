package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HistoricalTickFeedTest {
    @Test
    fun `next returns ticks in order then null`() {
        val ticks =
            listOf(
                Tick("XAUUSD", Money.of("100"), 1L),
                Tick("XAUUSD", Money.of("110"), 2L),
                Tick("XAUUSD", Money.of("105"), 3L),
            )
        val feed = HistoricalTickFeed(ticks)

        assertThat(feed.next()?.timestamp).isEqualTo(1L)
        assertThat(feed.next()?.timestamp).isEqualTo(2L)
        assertThat(feed.next()?.timestamp).isEqualTo(3L)
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `empty list returns null on first call`() {
        val feed = HistoricalTickFeed(emptyList())
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `repeated next calls past end keep returning null`() {
        val feed = HistoricalTickFeed(listOf(Tick("XAUUSD", Money.of("100"), 1L)))
        feed.next()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
    }
}
