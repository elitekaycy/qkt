package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Merges several per-symbol [SharedSubscriberFeed]s into one [TickFeed] for a multi-symbol
 * subscriber: one reader thread per feed puts into a shared queue; the feed ends when every
 * input ends, or at the first input that ends with a failure.
 */
internal class SharedFanInTickFeed(
    private val feeds: List<SharedSubscriberFeed>,
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
