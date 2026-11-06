package com.qkt.marketdata.source

import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FamilyTickFeedTest {
    private val ticks = listOf(Tick("A", BigDecimal.ONE, 1L), Tick("B", BigDecimal.TEN, 2L))

    private class Live(
        private val inner: TickFeed,
    ) : TickFeed by inner,
        MarketDataLifecycleFeed {
        val handlers = mutableListOf<(MarketDataFeedScope) -> Unit>()
        var closed = false

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            handlers += handler
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
            handlers += handler
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `each tick passes, followed by what each step makes of it`() {
        val steps: List<(Tick) -> Tick?> = listOf({ t -> t.copy(symbol = "${t.symbol}2") }, { null })
        val feed = FamilyTickFeed.over(HistoricalTickFeed(ticks), steps)

        val out = generateSequence { feed.next() }.map { it.symbol }.toList()

        assertThat(out).containsExactly("A", "A2", "B", "B2")
        assertThat(feed).isNotInstanceOf(MarketDataLifecycleFeed::class.java)
    }

    @Test
    fun `a live feed keeps its disconnects, reconnects and close`() {
        val inner = Live(HistoricalTickFeed(ticks))
        val feed = FamilyTickFeed.over(inner, listOf<(Tick) -> Tick?> { null })

        (feed as MarketDataLifecycleFeed).onDisconnect {}
        feed.onReconnect {}
        feed.close()

        assertThat(inner.handlers).hasSize(2)
        assertThat(inner.closed).isTrue()
        assertThat(feed.expectsContinuousDelivery).isTrue()
        assertThat(feed.next()?.symbol).isEqualTo("A")
    }
}
