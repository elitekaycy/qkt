package com.qkt.marketdata.live

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/** Vendor scope for a market-data lifecycle transition; null fields inherit the parent feed scope. */
data class MarketDataFeedScope(
    val source: String? = null,
    val symbols: List<String>? = null,
)

interface MarketDataLifecycleFeed {
    /** True when a null tick is an outage, not ordinary finite-feed exhaustion. */
    val expectsContinuousDelivery: Boolean get() = true

    fun onDisconnect(handler: (MarketDataFeedScope) -> Unit)

    fun onReconnect(handler: (MarketDataFeedScope) -> Unit)

    /** Failure detail populated when [TickFeed.next] terminates continuous delivery. */
    fun terminalFailureReason(): String? = null
}

/**
 * Pull-based [TickFeed] over a [LiveTickSource]. A transient source disconnect does not end the feed:
 * the source reconnects underneath (and re-subscribes) and `onReconnect` resumes ingestion. [next] only
 * gives up (returns `null`, ending the live session) on [close] or when a disconnect outlasts
 * [reconnectBudgetMs] — failing loud rather than hanging or silently running on stale data.
 */
class LiveTickFeed(
    private val source: LiveTickSource,
    queueCapacity: Int = 10_000,
    private val pollIntervalMs: Long = 200L,
    private val clock: Clock = SystemClock(),
    private val reconnectBudgetMs: Long = 120_000L,
) : TickFeed,
    MarketDataLifecycleFeed {
    private val log = LoggerFactory.getLogger(LiveTickFeed::class.java)

    private val queue: LinkedBlockingQueue<Tick> = LinkedBlockingQueue(queueCapacity)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val sourceDisconnected: AtomicBoolean = AtomicBoolean(false)
    private val disconnectedSinceMs: AtomicLong = AtomicLong(0)
    private val disconnectHandlers: CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit> = CopyOnWriteArrayList()
    private val reconnectHandlers: CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit> = CopyOnWriteArrayList()

    @Volatile
    private var terminalReason: String? = null

    val droppedTicks: AtomicLong = AtomicLong(0)

    init {
        source.start(
            onTick = { tick ->
                if (!queue.offer(tick)) {
                    // Buffer full: shed the oldest to make room for the freshest price.
                    // A concurrent consumer can empty the slot first, so poll() may come
                    // back null — only count a tick we actually evicted, or droppedTicks
                    // over-reports under load.
                    if (queue.poll() != null) droppedTicks.incrementAndGet()
                    queue.offer(tick)
                }
            },
            onError = { t -> log.warn("LiveTickFeed source error: ${t.message}", t) },
            onDisconnect = {
                log.warn("LiveTickFeed source disconnected; waiting up to ${reconnectBudgetMs}ms for reconnect")
                disconnectedSinceMs.set(clock.now())
                sourceDisconnected.set(true)
                disconnectHandlers.forEach { h -> runCatching { h(MarketDataFeedScope()) } }
            },
            onReconnect = {
                log.info("LiveTickFeed source reconnected; resuming")
                sourceDisconnected.set(false)
                reconnectHandlers.forEach { h -> runCatching { h(MarketDataFeedScope()) } }
            },
        )
    }

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        disconnectHandlers.add(handler)
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        reconnectHandlers.add(handler)
    }

    override fun terminalFailureReason(): String? = terminalReason

    override fun next(): Tick? {
        while (!closed.get()) {
            val t = queue.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
            if (t != null) return t
            if (sourceDisconnected.get()) {
                val down = clock.now() - disconnectedSinceMs.get()
                if (down > reconnectBudgetMs) {
                    terminalReason =
                        "market-data source exceeded its ${reconnectBudgetMs}ms reconnect budget " +
                        "after ${down}ms"
                    log.error(
                        "LiveTickFeed down ${down}ms (> budget ${reconnectBudgetMs}ms) without reconnect; ending feed",
                    )
                    return null
                }
                // Within budget: keep waiting for the source to reconnect and resume ticks.
            }
        }
        return queue.poll()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { source.stop() }
        }
    }
}
