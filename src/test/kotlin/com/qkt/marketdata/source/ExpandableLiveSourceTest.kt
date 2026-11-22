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
    /** A feed fed by the test: [push] a tick, [end] it, or make it [fail]. */
    private class Pushed(
        val symbols: List<String>,
        override val expectsContinuousDelivery: Boolean,
        private val reason: String?,
    ) : TickFeed,
        MarketDataLifecycleFeed {
        private val queue = LinkedBlockingQueue<Any>()
        val down = mutableListOf<(MarketDataFeedScope) -> Unit>()
        val up = mutableListOf<(MarketDataFeedScope) -> Unit>()

        @Volatile var closed = false

        fun push(tick: Tick) = queue.offer(tick)

        fun end() = queue.offer(Unit)

        fun fail() = queue.offer(IllegalStateException("socket reset"))

        override fun next(): Tick? =
            when (val item = queue.poll(5, TimeUnit.SECONDS)) {
                is Tick -> item
                is Exception -> throw item
                else -> null
            }

        override fun close() {
            closed = true
            end()
        }

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            down += handler
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
            up += handler
        }

        override fun terminalFailureReason(): String? = reason
    }

    private val opened = mutableListOf<Pushed>()
    private var continuous = true
    private var reason: String? = null

    private val venue =
        object : MarketSource {
            override val name = "Venue"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed {
                require("BAD" !in symbols) { "no such market" }
                return Pushed(symbols, continuous, reason).also { opened += it }
            }
        }

    private fun tick(
        symbol: String,
        atMs: Long,
    ) = Tick(symbol, BigDecimal.ONE, atMs)

    @Test
    fun `a running feed serves added symbols from one more feed and refuses what cannot be opened`() {
        var opens = 0
        var closes = 0
        val source = ExpandableLiveSource(venue, { opens++ }) { closes++ }
        assertThat(source.subscribe(listOf("B"))).isEmpty()
        source.unsubscribe(listOf("B"))
        val feed = source.liveTicks(listOf("A"))
        assertThat(opens).isEqualTo(1)
        opened.single().push(tick("A", 1))
        assertThat(feed.next()?.symbol).isEqualTo("A")
        assertThat(source.subscribe(listOf("A", "B"))).containsExactly("A", "B")
        assertThat(opened.last().symbols).containsExactly("B")
        opened.last().push(tick("B", 2))
        assertThat(feed.next()?.symbol).isEqualTo("B")
        assertThat(source.subscribe(listOf("BAD"))).isEmpty()
        assertThat(source.subscribe(listOf("B"))).containsExactly("B")
        assertThat(opened).hasSize(2)
        assertThat(source.subscribe(listOf("C"))).containsExactly("C")
        assertThat(opened.last().symbols).containsExactly("B", "C")
        assertThat(opened[1].closed).isTrue()
        opened.last().push(tick("C", 3))
        assertThat(feed.next()?.symbol).isEqualTo("C")
        opened.first().push(tick("A", 4))
        assertThat(feed.next()?.timestamp).isEqualTo(4L)
        feed.close()
        assertThat(feed.next()).isNull()
        assertThat(opened.filterNot { it.closed }).isEmpty()
        assertThat(closes).isEqualTo(1)
        assertThat(source.subscribe(listOf("D"))).isEmpty()
    }

    @Test
    fun `a dropped symbol is not asked for again and a tick the replaced feed delivered is not repeated`() {
        val source = ExpandableLiveSource(venue)
        val feed = source.liveTicks(listOf("A"))
        source.subscribe(listOf("B"))
        opened.last().push(tick("B", 10))
        assertThat(feed.next()?.timestamp).isEqualTo(10L)
        source.unsubscribe(listOf("A", "B"))
        assertThat(source.subscribe(listOf("B"))).containsExactly("B")
        assertThat(opened).hasSize(2)
        source.subscribe(listOf("C"))
        assertThat(opened.last().symbols).containsExactly("B", "C")
        opened.last().push(tick("B", 10))
        opened.last().push(tick("B", 11))
        assertThat(feed.next()?.timestamp).isEqualTo(11L)
        source.unsubscribe(listOf("B"))
        source.subscribe(listOf("D"))
        assertThat(opened.last().symbols).containsExactly("C", "D")
        feed.close()
    }

    /** The merged feed's failure after its added feed of B went through [stop]. */
    private fun stopped(stop: (Pushed) -> Unit): String? {
        val feed = ExpandableLiveSource(venue).liveTicks(listOf("A"))
        (feed as ExpandableTickFeed).add(listOf("B"))
        stop(opened.last())
        assertThat(feed.next()).isNull()
        assertThat((feed as MarketDataLifecycleFeed).expectsContinuousDelivery).isTrue()
        return feed.terminalFailureReason()
    }

    @Test
    fun `an added feed that fails or stops ends the merged feed, naming its symbols and the reason`() {
        assertThat(stopped { it.fail() }).isEqualTo("live feed of [B] stopped: socket reset")
        assertThat(stopped { it.end() }).isEqualTo("live feed of [B] stopped: it ended")
        reason = "venue said no"
        assertThat(stopped { it.end() }).isEqualTo("live feed of [B] stopped: venue said no")
    }

    @Test
    fun `a finite added feed that runs out only stops serving its symbols`() {
        continuous = false
        val feed = ExpandableLiveSource(venue).liveTicks(listOf("A"))
        (feed as ExpandableTickFeed).add(listOf("B"))
        opened.last().end()
        opened.first().push(tick("A", 3))
        assertThat(feed.next()?.timestamp).isEqualTo(3L)
        assertThat((feed as MarketDataLifecycleFeed).terminalFailureReason()).isNull()
        assertThat(feed.expectsContinuousDelivery).isFalse()
        opened.first().end()
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `outages of either feed reach the handlers, an added feed's naming its symbols`() {
        val feed = ExpandableLiveSource(venue).liveTicks(listOf("A")) as MarketDataLifecycleFeed
        val seen = mutableListOf<Pair<String, List<String>?>>()
        feed.onDisconnect { seen += "down" to it.symbols }
        feed.onReconnect { seen += "up" to it.symbols }
        (feed as ExpandableTickFeed).add(listOf("B"))
        for (source in opened) {
            source.down.forEach { it(MarketDataFeedScope()) }
            source.up.forEach { it(MarketDataFeedScope()) }
        }
        assertThat(seen).containsExactly("down" to null, "up" to null, "down" to listOf("B"), "up" to listOf("B"))
        feed.close()
    }

    @Test
    fun `a feed that can add symbols itself is used as it is`() {
        val native =
            object : ExpandableTickFeed {
                override fun add(symbols: Collection<String>) = symbols.toSet()

                override fun drop(symbols: Collection<String>) = Unit

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
        source.unsubscribe(listOf("Z"))
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
