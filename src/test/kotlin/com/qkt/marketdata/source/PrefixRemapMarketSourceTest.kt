package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrefixRemapMarketSourceTest {
    @Test
    fun `supports and capabilitiesFor translate the local prefix to the delegate prefix`() {
        val delegate = RecordingSource()
        val source = remap(delegate)

        assertThat(source.supports("S1:EURUSD")).isTrue()
        assertThat(source.supports("S2:EURUSD")).isFalse()
        assertThat(delegate.supportsQueries).containsExactly("S0:EURUSD")

        assertThat(source.capabilitiesFor("S1:EURUSD")).containsExactly(MarketSourceCapability.LIVE_TICKS)
        assertThat(delegate.capabilitiesQueries).containsExactly("S0:EURUSD")
    }

    @Test
    fun `liveTicks subscribes canonical symbols upstream and rewrites emitted ticks back`() {
        val delegate = RecordingSource()
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
        val delegate = RecordingSource()
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
        val delegate = RecordingSource(lifecycle = false)
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
        val delegate = RecordingSource()
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
        val source = remap(RecordingSource())

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

    private fun remap(delegate: RecordingSource): PrefixRemapMarketSource =
        PrefixRemapMarketSource(delegate = delegate, delegatePrefix = "S0:", localPrefix = "S1:")

    private fun tick(
        symbol: String,
        timestamp: Long,
    ): Tick = Tick(symbol, BigDecimal("1.00000"), timestamp)

    private class RecordingSource(
        private val lifecycle: Boolean = true,
    ) : MarketSource {
        override val name: String = "canonical"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        val supportsQueries = CopyOnWriteArrayList<String>()
        val capabilitiesQueries = CopyOnWriteArrayList<String>()
        val liveRequests = CopyOnWriteArrayList<List<String>>()
        val barRequests = CopyOnWriteArrayList<String>()
        val tickRequests = CopyOnWriteArrayList<String>()
        val tickSliceRequests = CopyOnWriteArrayList<String>()

        lateinit var feed: ControllableFeed
        lateinit var plainFeed: PlainFeed

        override fun supports(symbol: String): Boolean {
            supportsQueries.add(symbol)
            return symbol.startsWith("S0:")
        }

        override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> {
            capabilitiesQueries.add(symbol)
            return setOf(MarketSourceCapability.LIVE_TICKS)
        }

        override fun liveTicks(symbols: List<String>): TickFeed {
            liveRequests.add(symbols)
            return if (lifecycle) {
                ControllableFeed().also { feed = it }
            } else {
                PlainFeed().also { plainFeed = it }
            }
        }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            barRequests.add(symbol)
            return sequenceOf(
                Candle(
                    symbol = symbol,
                    open = BigDecimal("1.0"),
                    high = BigDecimal("1.1"),
                    low = BigDecimal("0.9"),
                    close = BigDecimal("1.05"),
                    volume = BigDecimal("10"),
                    startTime = range.from.toEpochMilli(),
                    endTime = range.to.toEpochMilli(),
                ),
            )
        }

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> {
            tickRequests.add(symbol)
            return sequenceOf(Tick(symbol, BigDecimal("1.00000"), range.from.toEpochMilli()))
        }

        override fun tickSlice(
            symbol: String,
            fromMs: Long,
            toMs: Long,
        ): Sequence<Tick> {
            tickSliceRequests.add(symbol)
            return sequenceOf(Tick(symbol, BigDecimal("1.00000"), fromMs))
        }
    }

    private class ControllableFeed :
        TickFeed,
        MarketDataLifecycleFeed {
        private sealed interface Item {
            data class Value(
                val tick: Tick,
            ) : Item

            data object End : Item
        }

        private val queue = LinkedBlockingQueue<Item>()
        val closed = AtomicBoolean(false)
        private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
        private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()

        @Volatile
        private var failure: String? = null

        fun emit(tick: Tick) {
            queue.put(Item.Value(tick))
        }

        fun disconnect(scope: MarketDataFeedScope) {
            disconnectHandlers.forEach { it(scope) }
        }

        fun reconnect(scope: MarketDataFeedScope) {
            reconnectHandlers.forEach { it(scope) }
        }

        fun fail(reason: String) {
            failure = reason
            queue.put(Item.End)
        }

        override fun next(): Tick? =
            when (val item = queue.take()) {
                is Item.Value -> item.tick
                Item.End -> null
            }

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            disconnectHandlers.add(handler)
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
            reconnectHandlers.add(handler)
        }

        override fun terminalFailureReason(): String? = failure

        override fun close() {
            closed.set(true)
            queue.offer(Item.End)
        }
    }

    private class PlainFeed : TickFeed {
        private val queue = LinkedBlockingQueue<Tick>()
        val closed = AtomicBoolean(false)

        fun emit(tick: Tick) {
            queue.put(tick)
        }

        override fun next(): Tick? = queue.take()

        override fun close() {
            closed.set(true)
        }
    }
}
