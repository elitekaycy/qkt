package com.qkt.app

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * What has held a session's engine thread: the messages whose handling took [thresholdMs] or longer, logged as they
 * happen with what they were (`event OrderFilled`, `heartbeat`, `query`) and counted. A read through [EngineSnapshot]
 * times out while one of them runs, so this names what the session was busy with when it did (#1609). Written by the
 * engine thread only, read from any.
 */
internal class EngineStallWatch(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
) {
    private val log = LoggerFactory.getLogger(LiveSession::class.java)
    private val count = AtomicLong(0)
    private val worst = AtomicReference(EngineStalls.NONE)

    /** Records that handling [message] held the thread [heldNanos]; a no-op under [thresholdMs]. */
    fun held(
        message: Inbound,
        heldNanos: Long,
    ) {
        val heldMs = heldNanos / NANOS_PER_MS
        if (heldMs < thresholdMs) return
        val n = count.incrementAndGet()
        val what = message.kind()
        if (heldMs > worst.get().worstHoldMs) worst.set(EngineStalls(n, heldMs, what))
        log.warn("engine thread held {} ms by {} ({} holds of {} ms or more so far)", heldMs, what, n, thresholdMs)
    }

    /** The holds so far and the longest of them. */
    fun snapshot(): EngineStalls = worst.get().copy(count = count.get())

    companion object {
        /** A hold this long already puts a 5 s engine read at risk once a few queue up. */
        const val DEFAULT_THRESHOLD_MS = 1_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}

/** How often a session's engine thread was held a second or more ([EngineStallWatch]), and the worst hold. */
data class EngineStalls(
    val count: Long,
    val worstHoldMs: Long,
    val worstHold: String?,
) {
    companion object {
        /** No hold seen. */
        val NONE = EngineStalls(0, 0, null)
    }
}

/** What a message on the engine loop is, for [EngineStallWatch]: cheap to call, called only for a long hold. */
internal fun Inbound.kind(): String =
    when (this) {
        is Inbound.FeedTick -> "tick ${tick.symbol}"
        is Inbound.BusEvent -> "event ${event::class.simpleName}"
        is Inbound.Heartbeat -> "heartbeat"
        is Inbound.Query -> "query"
        Inbound.Flatten -> "flatten"
        Inbound.PersistenceHealthCheck -> "persistence health check"
        is Inbound.FeedEnded -> "feed end"
        is Inbound.GracefulStop -> "graceful stop"
    }
