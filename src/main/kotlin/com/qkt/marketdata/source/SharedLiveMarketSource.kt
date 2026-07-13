package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shares one upstream live feed per symbol across every subscriber of a daemon-level source.
 *
 * Portfolio children are separate live sessions, but commonly consume the same venue symbols.
 * Without fan-out, each child opens another HTTP/WS subscription and multiplies venue load by
 * the number of strategies. Historical reads remain direct because they are bounded requests.
 */
class SharedLiveMarketSource(
    private val delegate: MarketSource,
    private val subscriberQueueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : MarketSource,
    AutoCloseable {
    override val name: String = delegate.name
    override val capabilities: Set<MarketSourceCapability> = delegate.capabilities

    private val closed = AtomicBoolean(false)
    private val hubs = ConcurrentHashMap<String, SymbolHub>()

    override fun supports(symbol: String): Boolean = delegate.supports(symbol)

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> = delegate.capabilitiesFor(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed {
        check(!closed.get()) { "$name is closed" }
        val distinct = symbols.distinct()
        require(distinct.isNotEmpty()) { "$name requires at least one live symbol" }
        require(distinct.all(delegate::supports)) { "$name cannot serve $distinct" }

        val feeds = distinct.map(::subscribe)
        return if (feeds.size == 1) feeds.single() else SharedFanInTickFeed(feeds)
    }

    private fun subscribe(symbol: String): SubscriberFeed {
        while (true) {
            val hub =
                hubs.compute(symbol) { _, current ->
                    current?.takeUnless { it.isClosed() }
                        ?: SymbolHub(symbol) { ended -> hubs.remove(symbol, ended) }
                }!!
            hub.subscribe()?.let { return it }
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = delegate.bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = delegate.ticks(symbol, range)

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> = delegate.tickSlice(symbol, fromMs, toMs)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        hubs.values.forEach { runCatching { it.close() } }
        hubs.clear()
        (delegate as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private inner class SymbolHub(
        private val symbol: String,
        private val onEnded: (SymbolHub) -> Unit,
    ) : AutoCloseable {
        private val lock = Any()
        private val subscribers = linkedSetOf<SubscriberFeed>()
        private val stopped = AtomicBoolean(false)
        private var upstream: TickFeed? = null
        private var publisher: Thread? = null

        fun isClosed(): Boolean = stopped.get()

        fun subscribe(): SubscriberFeed? =
            synchronized(lock) {
                if (stopped.get()) return@synchronized null
                val subscriber = SubscriberFeed(::unsubscribe, subscriberQueueCapacity)
                subscribers.add(subscriber)
                if (upstream == null) {
                    try {
                        startUpstream()
                    } catch (t: Throwable) {
                        subscribers.remove(subscriber)
                        stopped.set(true)
                        onEnded(this)
                        throw t
                    }
                }
                subscriber
            }

        private fun startUpstream() {
            val feed = delegate.liveTicks(listOf(symbol))
            upstream = feed
            (feed as? MarketDataLifecycleFeed)?.let { lifecycle ->
                lifecycle.onDisconnect { broadcastDisconnect() }
                lifecycle.onReconnect { broadcastReconnect() }
            }
            publisher =
                Thread(
                    {
                        var failure: String? = null
                        try {
                            while (!stopped.get()) {
                                val tick = feed.next()
                                if (tick == null) {
                                    val lifecycle = feed as? MarketDataLifecycleFeed
                                    failure =
                                        if (lifecycle?.expectsContinuousDelivery == true) {
                                            lifecycle.terminalFailureReason()
                                                ?: "continuous market-data feed ended"
                                        } else {
                                            null
                                        }
                                    break
                                }
                                snapshotSubscribers().forEach { it.offer(tick) }
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (t: Throwable) {
                            if (!stopped.get()) {
                                failure = "shared market-data publisher failed: ${t.message ?: t::class.simpleName}"
                            }
                        } finally {
                            terminate(failure)
                        }
                    },
                    "qkt-shared-feed-${name.hashCode()}-${symbol.hashCode()}",
                ).apply {
                    isDaemon = true
                    start()
                }
        }

        private fun snapshotSubscribers(): List<SubscriberFeed> = synchronized(lock) { subscribers.toList() }

        private fun broadcastDisconnect() {
            val scope = MarketDataFeedScope(source = name, symbols = listOf(symbol))
            snapshotSubscribers().forEach { it.disconnected(scope) }
        }

        private fun broadcastReconnect() {
            val scope = MarketDataFeedScope(source = name, symbols = listOf(symbol))
            snapshotSubscribers().forEach { it.reconnected(scope) }
        }

        private fun unsubscribe(subscriber: SubscriberFeed) {
            val stoppedByLastSubscriber =
                synchronized(lock) {
                    subscribers.remove(subscriber) &&
                        subscribers.isEmpty() &&
                        stopped.compareAndSet(false, true)
                }
            if (!stoppedByLastSubscriber) return
            runCatching { upstream?.close() }
            publisher?.interrupt()
            onEnded(this)
        }

        private fun terminate(failure: String?) {
            if (!stopped.compareAndSet(false, true)) return
            val remaining = synchronized(lock) { subscribers.toList().also { subscribers.clear() } }
            remaining.forEach { it.ended(failure) }
            runCatching { upstream?.close() }
            onEnded(this)
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            val remaining = synchronized(lock) { subscribers.toList().also { subscribers.clear() } }
            remaining.forEach { it.ended(null) }
            runCatching { upstream?.close() }
            publisher?.interrupt()
            onEnded(this)
        }
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 10_000
    }
}

private class SubscriberFeed(
    private val onClose: (SubscriberFeed) -> Unit,
    queueCapacity: Int,
) : TickFeed,
    MarketDataLifecycleFeed {
    private sealed interface Item {
        data class Value(
            val tick: Tick,
        ) : Item

        data object End : Item
    }

    private val queue = LinkedBlockingQueue<Item>(queueCapacity)
    private val closed = AtomicBoolean(false)
    private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()

    @Volatile
    private var terminalReason: String? = null

    fun offer(tick: Tick) {
        if (closed.get()) return
        if (!queue.offer(Item.Value(tick))) {
            queue.poll()
            queue.offer(Item.Value(tick))
        }
    }

    fun disconnected(scope: MarketDataFeedScope) {
        disconnectHandlers.forEach { handler -> runCatching { handler(scope) } }
    }

    fun reconnected(scope: MarketDataFeedScope) {
        reconnectHandlers.forEach { handler -> runCatching { handler(scope) } }
    }

    fun ended(failure: String?) {
        terminalReason = failure
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            queue.offer(Item.End)
        }
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

    override fun terminalFailureReason(): String? = terminalReason

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        queue.offer(Item.End)
        onClose(this)
    }
}

private class SharedFanInTickFeed(
    private val feeds: List<SubscriberFeed>,
) : TickFeed,
    MarketDataLifecycleFeed {
    private sealed interface Item {
        data class Value(
            val tick: Tick,
        ) : Item

        data class Ended(
            val index: Int,
            val failure: String?,
        ) : Item
    }

    private val queue = LinkedBlockingQueue<Item>(10_000)
    private val closed = AtomicBoolean(false)
    private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val ended = mutableSetOf<Int>()

    @Volatile
    private var terminalReason: String? = null

    private val readers =
        feeds.mapIndexed { index, feed ->
            feed.onDisconnect { scope -> disconnectHandlers.forEach { runCatching { it(scope) } } }
            feed.onReconnect { scope -> reconnectHandlers.forEach { runCatching { it(scope) } } }
            Thread(
                {
                    try {
                        while (!closed.get()) {
                            val tick = feed.next()
                            if (tick == null) {
                                queue.put(Item.Ended(index, feed.terminalFailureReason()))
                                return@Thread
                            }
                            queue.put(Item.Value(tick))
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
                "qkt-shared-fanin-$index",
            ).apply {
                isDaemon = true
                start()
            }
        }

    override fun next(): Tick? {
        while (!closed.get()) {
            when (val item = queue.take()) {
                is Item.Value -> return item.tick
                is Item.Ended -> {
                    ended.add(item.index)
                    if (item.failure != null) {
                        terminalReason = item.failure
                        close()
                        return null
                    }
                    if (ended.size == feeds.size) return null
                }
            }
        }
        return null
    }

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        disconnectHandlers.add(handler)
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        reconnectHandlers.add(handler)
    }

    override fun terminalFailureReason(): String? = terminalReason

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        feeds.forEach { runCatching { it.close() } }
        readers.forEach { it.interrupt() }
    }
}
