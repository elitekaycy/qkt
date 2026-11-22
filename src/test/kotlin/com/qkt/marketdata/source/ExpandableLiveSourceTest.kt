package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpandableLiveSourceTest {
    /** A feed fed by the test: [push] a tick, [end] it. */
    private class Pushed(
        val symbols: List<String>,
    ) : TickFeed,
        MarketDataLifecycleFeed {
        private val queue = LinkedBlockingQueue<Any>()
        var closed = false
        var disconnects = 0

        fun push(tick: Tick) = queue.offer(tick)

        fun end() = queue.offer(Unit)

        override fun next(): Tick? = queue.poll(5, TimeUnit.SECONDS) as? Tick

        override fun close() {
            closed = true
            end()
        }

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            disconnects++
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) = Unit

        override fun terminalFailureReason(): String? = "venue said no"
    }

    private val opened = mutableListOf<Pushed>()

    private val venue =
        object : MarketSource {
            override val name = "Venue"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed {
                require("BAD" !in symbols) { "no such market" }
                return Pushed(symbols).also { opened += it }
            }
        }

    private fun tick(
        symbol: String,
        atMs: Long,
    ) = Tick(symbol, BigDecimal.ONE, atMs)

    @Test
    fun `a running feed takes more symbols, serves their ticks and refuses what cannot be opened`() {
        var closedHook = 0
        val source = ExpandableLiveSource(venue) { closedHook++ }
        assertThat(source.subscribe(listOf("B"))).isEmpty()
        val feed = source.liveTicks(listOf("A"))
        opened.single().push(tick("A", 1))
        assertThat(feed.next()?.symbol).isEqualTo("A")
        assertThat(source.subscribe(listOf("A", "B"))).containsExactly("A", "B")
        assertThat(opened.last().symbols).containsExactly("B")
        opened.last().push(tick("B", 2))
        assertThat(feed.next()?.symbol).isEqualTo("B")
        assertThat(source.subscribe(listOf("BAD"))).isEmpty()
        assertThat(source.subscribe(listOf("B"))).containsExactly("B")
        assertThat(opened).hasSize(2)
        opened.last().end()
        opened.first().push(tick("A", 3))
        assertThat(feed.next()?.timestamp).isEqualTo(3L)
        val lifecycle = feed as MarketDataLifecycleFeed
        lifecycle.onDisconnect {}
        lifecycle.onReconnect {}
        assertThat(opened.first().disconnects).isEqualTo(1)
        assertThat(lifecycle.terminalFailureReason()).isEqualTo("venue said no")
        assertThat(lifecycle.expectsContinuousDelivery).isTrue()
        opened.first().end()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
        feed.close()
        assertThat(opened.all { it.closed }).isTrue()
        assertThat(closedHook).isEqualTo(1)
        assertThat(source.subscribe(listOf("C"))).isEmpty()
    }

    @Test
    fun `a feed that can add symbols itself is used as it is`() {
        val native =
            object : ExpandableTickFeed {
                override fun add(symbols: Collection<String>) = symbols.toSet()

                override fun next(): Tick? = null
            }
        val source =
            ExpandableLiveSource(
                object : MarketSource by venue {
                    override fun liveTicks(symbols: List<String>): TickFeed = native
                },
            )
        assertThat(source.liveTicks(listOf("A"))).isSameAs(native)
        assertThat(source.subscribe(listOf("Z"))).containsExactly("Z")
        native.close()
    }

    @Test
    fun `a primary feed that fails ends the merged feed`() {
        val failing =
            object : MarketSource by venue {
                override fun liveTicks(symbols: List<String>): TickFeed =
                    object : TickFeed {
                        override fun next(): Tick? = error("socket reset")
                    }
            }
        val feed = ExpandableLiveSource(failing).liveTicks(listOf("A"))
        assertThat(feed.next()).isNull()
        assertThat((feed as MarketDataLifecycleFeed).expectsContinuousDelivery).isFalse()
        assertThat(feed.terminalFailureReason()).isNull()
    }
}
