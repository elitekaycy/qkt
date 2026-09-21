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

/** A live source whose ticks, disconnects and failures a test drives by hand, one feed per symbol. */
internal fun tick(
    symbol: String,
    timestamp: Long,
): Tick = Tick(symbol, BigDecimal("1.00000"), timestamp)

internal class ControllableSource : MarketSource {
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

internal class ControllableFeed(
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
