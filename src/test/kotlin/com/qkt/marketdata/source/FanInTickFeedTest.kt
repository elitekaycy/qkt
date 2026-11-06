package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FanInTickFeedTest {
    private val failing =
        object : TickFeed {
            override fun next(): Tick? = throw IllegalStateException("socket reset")
        }

    @Test
    fun `a feed whose reader fails ends the merged feed naming the vendor and failure`() {
        val merged = FanInTickFeed(listOf(VendorTickFeed("venue", listOf("A", "B"), failing)))

        assertThat(merged.next()).isNull()
        assertThat(merged.terminalFailureReason())
            .isEqualTo("market-data feed 'venue' for A, B ended: feed reader failed: socket reset")
        assertThat(merged.next()).isNull()
    }

    @Test
    fun `a reconnect of one input reaches the merged feed with that vendor's scope`() {
        val live = ControllableFeed {}
        val merged = FanInTickFeed(listOf(VendorTickFeed("venue", listOf("A"), live)))
        val scopes = CopyOnWriteArrayList<MarketDataFeedScope>()
        merged.onReconnect { scopes += it }

        live.reconnect()
        merged.close()

        assertThat(scopes).containsExactly(MarketDataFeedScope("venue", listOf("A")))
        assertThat(merged.expectsContinuousDelivery).isTrue()
    }
}
