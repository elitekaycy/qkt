package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One input of a [FanInTickFeed]: a [source]'s [feed] of [symbols]. */
internal data class VendorTickFeed(
    val source: String,
    val symbols: List<String>,
    val feed: TickFeed,
)

/**
 * Merges live feeds into one [TickFeed], one reader thread per feed into a shared queue, so a feed that
 * blocks (a continuous stream measuring a roll) never stalls the others. It ends when every finite feed
 * has ended, or at the first continuous-delivery feed that ends (an outage), with that feed's reason;
 * each input's outages are passed on with its source and symbols.
 */
internal class FanInTickFeed(
    private val feeds: List<VendorTickFeed>,
) : TickFeed,
    MarketDataLifecycleFeed {
    private sealed interface Item {
        data class Value(
            val tick: Tick,
        ) : Item

        data class Ended(
            val vendor: VendorTickFeed,
            val failure: String?,
        ) : Item
    }

    private val queue = LinkedBlockingQueue<Item>(QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val threads: List<Thread>
    private var finiteFeedsEnded = 0

    @Volatile
    private var terminalReason: String? = null

    override val expectsContinuousDelivery: Boolean = feeds.any { it.feed is MarketDataLifecycleFeed }

    init {
        feeds.forEach { vendor ->
            (vendor.feed as? MarketDataLifecycleFeed)?.let { lifecycle ->
                lifecycle.onDisconnect {
                    val scope = MarketDataFeedScope(vendor.source, vendor.symbols)
                    disconnectHandlers.forEach { handler -> runCatching { handler(scope) } }
                }
                lifecycle.onReconnect {
                    val scope = MarketDataFeedScope(vendor.source, vendor.symbols)
                    reconnectHandlers.forEach { handler -> runCatching { handler(scope) } }
                }
            }
        }
        threads =
            feeds.mapIndexed { index, vendor ->
                Thread(
                    {
                        try {
                            while (!closed.get()) {
                                val tick = vendor.feed.next()
                                if (tick == null) {
                                    val lifecycle = vendor.feed as? MarketDataLifecycleFeed
                                    val failure =
                                        if (lifecycle?.expectsContinuousDelivery == true) {
                                            lifecycle.terminalFailureReason()
                                                ?: "continuous market-data feed ended"
                                        } else {
                                            null
                                        }
                                    queue.put(Item.Ended(vendor, failure))
                                    return@Thread
                                }
                                queue.put(Item.Value(tick))
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (t: Throwable) {
                            if (!closed.get()) {
                                queue.put(Item.Ended(vendor, "feed reader failed: ${t.message ?: t::class.simpleName}"))
                            }
                        }
                    },
                    "qkt-feed-fanin-$index",
                ).apply {
                    isDaemon = true
                    start()
                }
            }
    }

    override fun next(): Tick? {
        while (!closed.get()) {
            when (val item = queue.take()) {
                is Item.Value -> return item.tick
                is Item.Ended -> {
                    if (item.failure != null) {
                        terminalReason =
                            "market-data feed '${item.vendor.source}' for " +
                            "${item.vendor.symbols.joinToString()} ended: ${item.failure}"
                        close()
                        return null
                    }
                    finiteFeedsEnded++
                    if (finiteFeedsEnded == feeds.size) return null
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
        feeds.forEach { runCatching { it.feed.close() } }
        threads.forEach { it.interrupt() }
    }

    private companion object {
        const val QUEUE_CAPACITY = 10_000
    }
}
