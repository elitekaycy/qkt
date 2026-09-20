package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test

class CompositeMarketSourceFanInTest {
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
