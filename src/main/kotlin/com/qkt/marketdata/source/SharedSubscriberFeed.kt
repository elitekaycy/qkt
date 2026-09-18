package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One subscriber's view of a shared symbol feed: a bounded queue the hub's publisher thread
 * offers into (dropping the oldest tick when full), plus the subscriber's own disconnect and
 * reconnect handlers. Closing it unsubscribes from the hub.
 */
internal class SharedSubscriberFeed(
    private val onClose: (SharedSubscriberFeed) -> Unit,
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
