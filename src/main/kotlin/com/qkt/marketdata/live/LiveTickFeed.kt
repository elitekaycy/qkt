package com.qkt.marketdata.live

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

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
) : TickFeed {
    private val log = LoggerFactory.getLogger(LiveTickFeed::class.java)

    private val queue: LinkedBlockingQueue<Tick> = LinkedBlockingQueue(queueCapacity)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val sourceDisconnected: AtomicBoolean = AtomicBoolean(false)
    private val disconnectedSinceMs: AtomicLong = AtomicLong(0)

    val droppedTicks: AtomicLong = AtomicLong(0)

    init {
        source.start(
            onTick = { tick ->
                if (!queue.offer(tick)) {
                    queue.poll()
                    droppedTicks.incrementAndGet()
                    queue.offer(tick)
                }
            },
            onError = { t -> log.warn("LiveTickFeed source error: ${t.message}", t) },
            onDisconnect = {
                log.warn("LiveTickFeed source disconnected; waiting up to ${reconnectBudgetMs}ms for reconnect")
                disconnectedSinceMs.set(clock.now())
                sourceDisconnected.set(true)
            },
            onReconnect = {
                log.info("LiveTickFeed source reconnected; resuming")
                sourceDisconnected.set(false)
            },
        )
    }

    override fun next(): Tick? {
        while (!closed.get()) {
            val t = queue.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
            if (t != null) return t
            if (sourceDisconnected.get()) {
                val down = clock.now() - disconnectedSinceMs.get()
                if (down > reconnectBudgetMs) {
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
