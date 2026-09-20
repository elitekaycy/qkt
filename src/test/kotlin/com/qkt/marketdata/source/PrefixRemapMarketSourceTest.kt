package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrefixRemapMarketSourceTest {
    @Test
    fun `supports and capabilitiesFor translate the local prefix to the delegate prefix`() {
        val delegate = RemapRecordingSource()
        val source = remap(delegate)

        assertThat(source.supports("S1:EURUSD")).isTrue()
        assertThat(source.supports("S2:EURUSD")).isFalse()
        assertThat(delegate.supportsQueries).containsExactly("S0:EURUSD")

        assertThat(source.capabilitiesFor("S1:EURUSD")).containsExactly(MarketSourceCapability.LIVE_TICKS)
        assertThat(delegate.capabilitiesQueries).containsExactly("S0:EURUSD")
    }

    @Test
    fun `liveTicks subscribes canonical symbols upstream and rewrites emitted ticks back`() {
        val delegate = RemapRecordingSource()
        val source = remap(delegate)

        val feed = source.liveTicks(listOf("S1:EURUSD", "S1:XAUUSD"))
        assertThat(delegate.liveRequests).containsExactly(listOf("S0:EURUSD", "S0:XAUUSD"))

        delegate.feed.emit(tick("S0:EURUSD", 1L))
        assertThat(feed.next()).isEqualTo(tick("S1:EURUSD", 1L))

        feed.close()
        assertThat(delegate.feed.closed.get()).isTrue()
    }

    @Test
    fun `end-of-feed propagates and lifecycle handlers see rewritten scope symbols`() {
        val delegate = RemapRecordingSource()
        val source = remap(delegate)
        val feed = source.liveTicks(listOf("S1:EURUSD"))
        val lifecycle = feed as MarketDataLifecycleFeed

        val disconnects = CopyOnWriteArrayList<MarketDataFeedScope>()
        val reconnects = CopyOnWriteArrayList<MarketDataFeedScope>()
        lifecycle.onDisconnect { disconnects.add(it) }
        lifecycle.onReconnect { reconnects.add(it) }

        delegate.feed.disconnect(MarketDataFeedScope(source = "canonical", symbols = listOf("S0:EURUSD")))
        delegate.feed.reconnect(MarketDataFeedScope(symbols = listOf("S0:EURUSD")))
        assertThat(disconnects)
            .containsExactly(MarketDataFeedScope(source = "canonical", symbols = listOf("S1:EURUSD")))
        assertThat(reconnects).containsExactly(MarketDataFeedScope(symbols = listOf("S1:EURUSD")))

        // A scope without symbols stays symbol-less.
        delegate.feed.disconnect(MarketDataFeedScope())
        assertThat(disconnects.last().symbols).isNull()

        assertThat(lifecycle.expectsContinuousDelivery).isTrue()
        delegate.feed.fail("venue unavailable")
        assertThat(feed.next()).isNull()
        assertThat(lifecycle.terminalFailureReason()).isEqualTo("venue unavailable")
    }

    @Test
    fun `plain TickFeed delegate yields a plain TickFeed without lifecycle contract`() {
        val delegate = RemapRecordingSource(lifecycle = false)
        val source = remap(delegate)

        val feed = source.liveTicks(listOf("S1:EURUSD"))
        assertThat(feed).isNotInstanceOf(MarketDataLifecycleFeed::class.java)

        delegate.plainFeed.emit(tick("S0:EURUSD", 2L))
        assertThat(feed.next()).isEqualTo(tick("S1:EURUSD", 2L))
        feed.close()
        assertThat(delegate.plainFeed.closed.get()).isTrue()
    }

    @Test
    fun `bars ticks and tickSlice translate the request and restamp results with the local symbol`() {
        val delegate = RemapRecordingSource()
        val source = remap(delegate)
        val range = TimeRange(Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L))

        val bars = source.bars("S1:EURUSD", TimeWindow.parse("1m"), range).toList()
        assertThat(delegate.barRequests).containsExactly("S0:EURUSD")
        assertThat(bars.map { it.symbol }).containsExactly("S1:EURUSD")

        val ticks = source.ticks("S1:EURUSD", range).toList()
        assertThat(delegate.tickRequests).containsExactly("S0:EURUSD")
        assertThat(ticks.map { it.symbol }).containsExactly("S1:EURUSD")

        val slice = source.tickSlice("S1:EURUSD", 0L, 60_000L).toList()
        assertThat(delegate.tickSliceRequests).containsExactly("S0:EURUSD")
        assertThat(slice.map { it.symbol }).containsExactly("S1:EURUSD")
    }

    @Test
    fun `symbols outside the local prefix are rejected`() {
        val source = remap(RemapRecordingSource())

        org.assertj.core.api.Assertions
            .assertThatThrownBy { source.liveTicks(listOf("S2:EURUSD")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                source
                    .bars(
                        "S2:EURUSD",
                        TimeWindow.parse("1m"),
                        TimeRange(Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L)),
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun remap(delegate: RemapRecordingSource): PrefixRemapMarketSource =
        PrefixRemapMarketSource(delegate = delegate, delegatePrefix = "S0:", localPrefix = "S1:")

    private fun tick(
        symbol: String,
        timestamp: Long,
    ): Tick = Tick(symbol, BigDecimal("1.00000"), timestamp)
}
