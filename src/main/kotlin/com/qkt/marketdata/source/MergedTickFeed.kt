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
 * One live feed over a [primary] feed of [symbols] and one more feed that [open] opens for the symbols added later
 * ([add]), each read by its own daemon pump into one queue, so ticks reach the reader in arrival order whichever
 * feed they came from.
 *
 * - There is never more than one added feed. Adding symbols opens one feed for every added symbol still wanted
 *   (those not [drop]ped), then closes the feed it replaces: e.g. added `[B]`, then add `[C]` after `B` was dropped
 *   → one feed of `[C]`. A tick the replaced feed already delivered is not delivered again.
 * - The feed ends when the primary does. It also ends, with a [terminalFailureReason], when the added feed fails or
 *   stops although it should deliver without end: its symbols would otherwise look fed while no tick arrives, so
 *   the session stops instead, as it does when its only feed gives up. A finite added feed that runs out just stops.
 * - Outages of either feed reach the handlers registered here; an added feed's name its own symbols.
 * - Closing it closes every feed and runs [onClose].
 */
internal class MergedTickFeed(
    private val primary: TickFeed,
    symbols: Collection<String>,
    private val open: (List<String>) -> TickFeed,
    private val onClose: () -> Unit = {},
) : ExpandableTickFeed,
    MarketDataLifecycleFeed {
    /** The added feed that serves [symbols] until it is [retired] by its replacement. */
    private class Added(
        val feed: TickFeed,
        val symbols: List<String>,
    ) {
        var retired = false
    }

    private val log = LoggerFactory.getLogger(MergedTickFeed::class.java)
    private val queue = LinkedBlockingQueue<Any>()
    private val first = symbols.toSet()
    private val extra = LinkedHashSet<String>()
    private val dropped = HashSet<String>()
    private val lastMs = ConcurrentHashMap<String, Long>()
    private val disconnects = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val reconnects = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private var added: Added? = null

    @Volatile private var closed = false

    @Volatile private var failure: String? = null

    init {
        (primary as? MarketDataLifecycleFeed)?.let { feed ->
            feed.onDisconnect { scope -> disconnects.forEach { runCatching { it(scope) } } }
            feed.onReconnect { scope -> reconnects.forEach { runCatching { it(scope) } } }
        }
        Thread({
            try {
                while (!closed) queue.offer(primary.next() ?: break)
            } catch (e: Exception) {
                if (!closed) log.warn("live feed stopped: {}", e.message)
            }
            queue.offer(END)
        }, "merged-feed-primary").apply { isDaemon = true }.start()
    }

    override val expectsContinuousDelivery: Boolean
        get() = failure != null || (primary as? MarketDataLifecycleFeed)?.expectsContinuousDelivery ?: false

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        disconnects += handler
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        reconnects += handler
    }

    override fun terminalFailureReason(): String? =
        failure ?: (primary as? MarketDataLifecycleFeed)?.terminalFailureReason()

    @Synchronized
    override fun add(symbols: Collection<String>): Set<String> {
        dropped -= symbols.toSet()
        val fresh = symbols.filter { it !in first && it !in extra }.distinct()
        if (fresh.isNotEmpty() && !closed) {
            val wanted = extra.filter { it !in dropped } + fresh
            runCatching { open(wanted) }
                .onFailure { log.warn("cannot subscribe {}: {}", wanted, it.message) }
                .getOrNull()
                ?.let { replace(Added(it, wanted)) }
        }
        return symbols.filterTo(LinkedHashSet()) { it in first || it in extra }
    }

    @Synchronized
    override fun drop(symbols: Collection<String>) {
        dropped += symbols.filter { it in extra }
    }

    override fun next(): Tick? {
        if (closed) return null
        return when (val item = queue.take()) {
            is Tick -> item
            else -> null.also { closed = true }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        onClose()
        runCatching { primary.close() }
        added?.let { runCatching { it.feed.close() } }
        queue.offer(END)
    }

    /** Serves the added symbols from [next] from now on, and closes the feed it replaces. */
    private fun replace(next: Added) {
        val previous = added
        previous?.let { synchronized(it) { it.retired = true } }
        val floor = HashMap(lastMs)
        previous?.let { runCatching { it.feed.close() } }
        extra.clear()
        extra += next.symbols
        dropped.clear()
        lastMs.keys.retainAll(extra)
        added = next
        (next.feed as? MarketDataLifecycleFeed)?.let { feed ->
            feed.onDisconnect { scope -> disconnects.forEach { runCatching { it(scoped(scope, next)) } } }
            feed.onReconnect { scope -> reconnects.forEach { runCatching { it(scoped(scope, next)) } } }
        }
        Thread({ pump(next, floor) }, "merged-feed-added").apply { isDaemon = true }.start()
    }

    private fun scoped(
        scope: MarketDataFeedScope,
        feed: Added,
    ): MarketDataFeedScope = scope.copy(symbols = scope.symbols ?: feed.symbols)

    /** Reads [added] until it is retired or ends; ticks at or before [floor] were delivered by the feed before it. */
    private fun pump(
        added: Added,
        floor: Map<String, Long>,
    ) {
        var error: String? = null
        try {
            while (!closed) {
                val tick = added.feed.next() ?: break
                if (tick.timestamp <= (floor[tick.symbol] ?: Long.MIN_VALUE)) continue
                synchronized(added) {
                    if (added.retired) return
                    lastMs[tick.symbol] = tick.timestamp
                    queue.offer(tick)
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        if (closed || synchronized(added) { added.retired }) return
        val lifecycle = added.feed as? MarketDataLifecycleFeed
        val reason = error ?: lifecycle?.terminalFailureReason()
        if (reason == null && lifecycle?.expectsContinuousDelivery != true) return
        failure = "live feed of ${added.symbols} stopped: ${reason ?: "it ended"}"
        log.warn("{}; ending the session's feed", failure)
        queue.offer(END)
    }

    private companion object {
        val END = Any()
    }
}
