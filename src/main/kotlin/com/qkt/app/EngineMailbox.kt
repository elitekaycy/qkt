package com.qkt.app

import com.qkt.app.LiveSession.Companion.TICK_QUEUE_CAPACITY
import com.qkt.events.Event
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Everything other threads use to reach a live session's engine loop, and the flags that say
 * where the session is in its life.
 *
 * Control events (bus events from pollers, flatten, heartbeat, feed-end) are
 * low-rate and must NEVER be dropped; ticks are high-rate and individually
 * disposable — a newer tick supersedes an older one. Splitting them bounds
 * memory under a stalled consumer: the tick queue sheds its OLDEST on overflow, so one
 * stalled engine thread cannot OOM the daemon, e.g. tick 10,001 of a backlog evicts tick 1.
 * The loop drains control ahead of ticks, so a flatten or fill never waits behind a tick backlog.
 */
internal class EngineMailbox {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    val running = AtomicBoolean(true)
    val stopping = AtomicBoolean(false)
    val stopFinishing = AtomicBoolean(false)
    val clearRuleEdgesAtStop = AtomicBoolean(false)
    val control = java.util.concurrent.LinkedBlockingQueue<Inbound>()
    val tickQueue = java.util.concurrent.ArrayBlockingQueue<Inbound.FeedTick>(TICK_QUEUE_CAPACITY)
    val droppedInboundTicks =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Counted down by the engine thread once its final drain is done. */
    val terminated = CountDownLatch(1)

    /** Queue a bus event for the engine thread; dropped once the session stopped running. */
    fun postBusEvent(ev: Event) {
        if (running.get()) control.put(Inbound.BusEvent(ev))
    }

    /** Queue a feed tick, shedding the oldest queued tick when the queue is full. */
    fun postTick(msg: Inbound.FeedTick) {
        while (!tickQueue.offer(msg)) {
            if (tickQueue.poll() != null) {
                val dropped = droppedInboundTicks.incrementAndGet()
                if (dropped == 1L) {
                    log.warn(
                        "inbound tick queue saturated (capacity {}) — shedding oldest ticks; " +
                            "the engine thread is not keeping up",
                        TICK_QUEUE_CAPACITY,
                    )
                }
            }
        }
    }
}
