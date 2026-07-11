package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

class CompositeMarketSourceTest {
    private class FakeSource(
        override val name: String,
        override val capabilities: Set<MarketSourceCapability>,
        private val supportedPrefixes: List<String>,
    ) : MarketSource {
        var lastBarsSymbol: String? = null
        var lastTicksSymbol: String? = null

        override fun supports(symbol: String): Boolean = supportedPrefixes.any { symbol.startsWith(it) }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            lastBarsSymbol = symbol
            return emptySequence()
        }

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> {
            lastTicksSymbol = symbol
            return emptySequence()
        }
    }

    private class FeedSource(
        override val name: String,
        private val prefix: String,
        private val feed: TickFeed,
    ) : MarketSource {
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = symbol.startsWith(prefix)

        override fun liveTicks(symbols: List<String>) = feed
    }

    private class LifecycleTestFeed :
        TickFeed,
        MarketDataLifecycleFeed {
        private val ended = CountDownLatch(1)
        private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
        private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()

        override fun next(): Tick? {
            ended.await()
            return null
        }

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            disconnectHandlers.add(handler)
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
            reconnectHandlers.add(handler)
        }

        fun disconnect() {
            disconnectHandlers.forEach { it(MarketDataFeedScope()) }
        }

        fun end() {
            ended.countDown()
        }

        override fun close() {
            ended.countDown()
        }
    }

    @Test
    fun `routes bar query to source whose pattern matches`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:", "BINANCE:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        composite.bars("OANDA:EURUSD", TimeWindow.ONE_MINUTE, range).toList()

        assertThat(tv.lastBarsSymbol).isEqualTo("OANDA:EURUSD")
        assertThat(local.lastBarsSymbol).isNull()
    }

    @Test
    fun `falls back when no pattern matches`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        composite.bars("EURUSD", TimeWindow.ONE_MINUTE, range).toList()

        assertThat(local.lastBarsSymbol).isEqualTo("EURUSD")
        assertThat(tv.lastBarsSymbol).isNull()
    }

    @Test
    fun `capabilities is the union of all routes plus fallback`() {
        val tv =
            FakeSource("TV", setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        assertThat(composite.capabilities).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
            MarketSourceCapability.TICKS,
        )
    }

    @Test
    fun `supports returns true if any route or fallback supports`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.TICKS), listOf("LOCAL:"))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        assertThat(composite.supports("OANDA:EURUSD")).isTrue()
        assertThat(composite.supports("LOCAL:X")).isTrue()
        assertThat(composite.supports("UNKNOWN")).isFalse()
    }

    @Test
    fun `live ticks throws when neither route nor fallback supports it`() {
        val barsOnly = FakeSource("BarsOnly", setOf(MarketSourceCapability.BARS), listOf(""))
        val composite = CompositeMarketSource(routes = emptyList(), fallback = barsOnly)
        assertThatThrownBy { composite.liveTicks(listOf("X")) }
            .isInstanceOf(UnsupportedDataException::class.java)
    }

    @Test
    fun `quiet vendor does not block ticks from a healthy vendor`() {
        val quietClosed = CountDownLatch(1)
        val quietFeed =
            object : TickFeed {
                override fun next(): Tick? {
                    quietClosed.await()
                    return null
                }

                override fun close() {
                    quietClosed.countDown()
                }
            }
        val healthyTick = Tick("B:X", java.math.BigDecimal("10"), 1L)
        val healthyTicks = ArrayDeque(listOf(healthyTick))
        val healthyFeed =
            object : TickFeed {
                override fun next(): Tick? = healthyTicks.removeFirstOrNull()
            }
        val quiet = FeedSource("quiet", "A:", quietFeed)
        val healthy = FeedSource("healthy", "B:", healthyFeed)
        val feed =
            CompositeMarketSource(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to quiet,
                        SymbolPattern.prefix("B:") to healthy,
                    ),
                fallback = quiet,
            ).liveTicks(listOf("A:X", "B:X"))

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThat(feed.next()).isEqualTo(healthyTick)
        }
        feed.close()
    }

    @Test
    fun `fan in forwards lifecycle events with vendor scope`() {
        val firstFeed = LifecycleTestFeed()
        val secondFeed = LifecycleTestFeed()
        val first = FeedSource("first-vendor", "A:", firstFeed)
        val second = FeedSource("second-vendor", "B:", secondFeed)
        val feed =
            CompositeMarketSource(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to first,
                        SymbolPattern.prefix("B:") to second,
                    ),
                fallback = first,
            ).liveTicks(listOf("A:X", "B:Y")) as MarketDataLifecycleFeed
        val disconnected = mutableListOf<MarketDataFeedScope>()
        feed.onDisconnect { disconnected.add(it) }

        firstFeed.disconnect()

        assertThat(disconnected).containsExactly(MarketDataFeedScope("first-vendor", listOf("A:X")))
        (feed as TickFeed).close()
    }

    @Test
    fun `continuous vendor ending terminates fan in with vendor reason`() {
        val failedFeed = LifecycleTestFeed()
        val quietFeed = LifecycleTestFeed()
        val failed = FeedSource("failed-vendor", "A:", failedFeed)
        val quiet = FeedSource("quiet-vendor", "B:", quietFeed)
        val feed =
            CompositeMarketSource(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to failed,
                        SymbolPattern.prefix("B:") to quiet,
                    ),
                fallback = failed,
            ).liveTicks(listOf("A:X", "B:Y"))
        failedFeed.end()

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThat(feed.next()).isNull()
        }
        assertThat((feed as MarketDataLifecycleFeed).terminalFailureReason())
            .contains("failed-vendor")
            .contains("A:X")
        feed.close()
    }
}
