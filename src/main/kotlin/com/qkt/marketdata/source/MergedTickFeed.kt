package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import org.slf4j.LoggerFactory

/**
 * One live feed over a [primary] feed of [symbols] and the feeds [open] opens for symbols added later ([add]), each
 * read by its own daemon pump into one queue, so ticks reach the reader in arrival order whichever feed they came
 * from. The feed ends when the primary does; an added feed that ends or fails only stops serving its symbols. The
 * primary's market-data lifecycle (disconnects, reconnects, terminal failure) is the merged feed's; closing it closes
 * every feed and runs [onClose].
 */
internal class MergedTickFeed(
    private val primary: TickFeed,
    symbols: Collection<String>,
    private val open: (List<String>) -> TickFeed,
    private val onClose: () -> Unit = {},
) : ExpandableTickFeed,
    MarketDataLifecycleFeed {
    private val log = LoggerFactory.getLogger(MergedTickFeed::class.java)
    private val queue = LinkedBlockingQueue<Any>()
    private val served = ConcurrentHashMap.newKeySet<String>().apply { addAll(symbols) }
    private val added = CopyOnWriteArrayList<TickFeed>()

    @Volatile private var closed = false

    init {
        pump(primary, primary = true)
    }

    override val expectsContinuousDelivery: Boolean
        get() = (primary as? MarketDataLifecycleFeed)?.expectsContinuousDelivery ?: false

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        (primary as? MarketDataLifecycleFeed)?.onDisconnect(handler)
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        (primary as? MarketDataLifecycleFeed)?.onReconnect(handler)
    }

    override fun terminalFailureReason(): String? = (primary as? MarketDataLifecycleFeed)?.terminalFailureReason()

    @Synchronized
    override fun add(symbols: Collection<String>): Set<String> {
        val fresh = symbols.filter { it !in served }.distinct()
        if (fresh.isNotEmpty() && !closed) {
            val feed = runCatching { open(fresh) }.onFailure { log.warn("cannot subscribe {}: {}", fresh, it.message) }
            feed.getOrNull()?.let {
                added += it
                served += fresh
                pump(it, primary = false)
            }
        }
        return symbols.filterTo(LinkedHashSet()) { it in served }
    }

    override fun next(): Tick? {
        if (closed) return null
        return when (val item = queue.take()) {
            is Tick -> item
            else -> null.also { closed = true }
        }
    }

    override fun close() {
        closed = true
        onClose()
        runCatching { primary.close() }
        added.forEach { runCatching { it.close() } }
        queue.offer(END)
    }

    private fun pump(
        feed: TickFeed,
        primary: Boolean,
    ) {
        Thread({
            try {
                while (!closed) queue.offer(feed.next() ?: break)
            } catch (e: Exception) {
                if (!closed) log.warn("live feed stopped: {}", e.message)
            }
            if (primary) queue.offer(END)
        }, "merged-feed-${if (primary) "primary" else "added"}").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        val END = Any()
    }
}
