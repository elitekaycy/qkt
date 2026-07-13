package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SharedLiveMarketSourceTest {
    @Test
    fun `overlapping subscribers share one upstream feed per symbol`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)

        val first = shared.liveTicks(listOf("A", "B"))
        val second = shared.liveTicks(listOf("B", "C"))

        assertThat(delegate.openCount("A")).isEqualTo(1)
        assertThat(delegate.openCount("B")).isEqualTo(1)
        assertThat(delegate.openCount("C")).isEqualTo(1)

        val tick = tick("B", 1L)
        delegate.emit(tick)
        assertThat(first.next()).isEqualTo(tick)
        assertThat(second.next()).isEqualTo(tick)

        first.close()
        val later = tick("B", 2L)
        delegate.emit(later)
        assertThat(second.next()).isEqualTo(later)
        assertThat(delegate.closeCount("B")).isZero()

        second.close()
        assertThat(delegate.awaitClosed("B")).isTrue()
    }

    @Test
    fun `last subscriber closes upstream and a later subscriber opens a fresh feed`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)

        shared.liveTicks(listOf("A")).close()
        assertThat(delegate.awaitClosed("A")).isTrue()

        val replacement = shared.liveTicks(listOf("A"))
        assertThat(delegate.openCount("A")).isEqualTo(2)
        val tick = tick("A", 3L)
        delegate.emit(tick)
        assertThat(replacement.next()).isEqualTo(tick)
        replacement.close()
    }

    @Test
    fun `disconnect reconnect and terminal failure propagate to every subscriber`() {
        val delegate = ControllableSource()
        val shared = SharedLiveMarketSource(delegate)
        val first = shared.liveTicks(listOf("A"))
        val second = shared.liveTicks(listOf("A"))
        val disconnected = CountDownLatch(2)
        val reconnected = CountDownLatch(2)
        val scopes = CopyOnWriteArrayList<MarketDataFeedScope>()

        listOf(first, second).forEach { feed ->
            val lifecycle = feed as MarketDataLifecycleFeed
            lifecycle.onDisconnect { scope ->
                scopes.add(scope)
                disconnected.countDown()
            }
            lifecycle.onReconnect { reconnected.countDown() }
        }

        delegate.disconnect("A")
        assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(scopes).allSatisfy { scope ->
            assertThat(scope.source).isEqualTo("controlled")
            assertThat(scope.symbols).containsExactly("A")
        }

        delegate.reconnect("A")
        assertThat(reconnected.await(2, TimeUnit.SECONDS)).isTrue()

        delegate.fail("A", "venue unavailable")
        assertThat(first.next()).isNull()
        assertThat(second.next()).isNull()
        assertThat((first as MarketDataLifecycleFeed).terminalFailureReason()).isEqualTo("venue unavailable")
        assertThat((second as MarketDataLifecycleFeed).terminalFailureReason()).isEqualTo("venue unavailable")
    }

    private fun tick(
        symbol: String,
        timestamp: Long,
    ): Tick = Tick(symbol, BigDecimal("1.00000"), timestamp)

    private class ControllableSource : MarketSource {
        override val name: String = "controlled"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        private val feeds = ConcurrentHashMap<String, ControllableFeed>()
        private val opens = ConcurrentHashMap<String, AtomicInteger>()
        private val closes = ConcurrentHashMap<String, AtomicInteger>()
        private val closed = ConcurrentHashMap<String, CountDownLatch>()

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed {
            require(symbols.size == 1)
            val symbol = symbols.single()
            opens.computeIfAbsent(symbol) { AtomicInteger() }.incrementAndGet()
            closed[symbol] = CountDownLatch(1)
            return ControllableFeed {
                closes.computeIfAbsent(symbol) { AtomicInteger() }.incrementAndGet()
                closed.getValue(symbol).countDown()
            }.also { feeds[symbol] = it }
        }

        fun openCount(symbol: String): Int = opens[symbol]?.get() ?: 0

        fun closeCount(symbol: String): Int = closes[symbol]?.get() ?: 0

        fun awaitClosed(symbol: String): Boolean = closed.getValue(symbol).await(2, TimeUnit.SECONDS)

        fun emit(tick: Tick) = feeds.getValue(tick.symbol).emit(tick)

        fun disconnect(symbol: String) = feeds.getValue(symbol).disconnect()

        fun reconnect(symbol: String) = feeds.getValue(symbol).reconnect()

        fun fail(
            symbol: String,
            reason: String,
        ) = feeds.getValue(symbol).fail(reason)
    }

    private class ControllableFeed(
        private val onClose: () -> Unit,
    ) : TickFeed,
        MarketDataLifecycleFeed {
        private sealed interface Item {
            data class Value(
                val tick: Tick,
            ) : Item

            data object End : Item
        }

        private val queue = LinkedBlockingQueue<Item>()
        private val closed = AtomicBoolean(false)
        private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
        private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()

        @Volatile
        private var failure: String? = null

        fun emit(tick: Tick) {
            queue.put(Item.Value(tick))
        }

        fun disconnect() {
            disconnectHandlers.forEach { it(MarketDataFeedScope()) }
        }

        fun reconnect() {
            reconnectHandlers.forEach { it(MarketDataFeedScope()) }
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
            if (!closed.compareAndSet(false, true)) return
            queue.offer(Item.End)
            onClose()
        }
    }
}
