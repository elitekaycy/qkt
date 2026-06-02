package com.qkt.notify

import com.qkt.notify.TelegramClient.Outcome
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * Drains a bounded queue and delivers each item via [send]. Owns one daemon thread.
 *
 * Retry policy: each [send] call that returns [Outcome.TransientError] / [Outcome.NetworkError]
 * is retried after the next entry in [backoffMs]. A [Outcome.RateLimited] result is retried
 * after the venue-supplied delay. [Outcome.AuthFailed] flips degraded mode — every further
 * enqueue is dropped and logged, until the daemon is restarted.
 *
 * Drop-on-overflow: [enqueue] uses [ArrayBlockingQueue.offer] (non-blocking). Returns false
 * when the queue is full; the worker increments `dropped` and logs once per ~minute.
 */
class NotificationWorker(
    private val send: (String) -> Outcome,
    private val metrics: AtomicNotifierMetrics,
    private val queueCapacity: Int = 100,
    private val backoffMs: List<Long> = listOf(1_000L, 5_000L, 30_000L),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(NotificationWorker::class.java)
    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val running = AtomicBoolean(true)

    // Items accepted into the queue but not yet fully delivered. Incremented before the
    // item is offered, so the worker can never dequeue-and-decrement ahead of the producer;
    // decremented only after delivery completes. flush() waits for this to reach zero —
    // unlike a separate queue.isEmpty()/inFlight pair, there is no gap between "removed from
    // queue" and "marked busy" for flush to mistake for idle.
    private val pending = AtomicInteger(0)

    @Volatile private var lastDropLogMs: Long = 0L

    private val thread =
        Thread({ runLoop() }, "qkt-notify").apply {
            isDaemon = true
            start()
        }

    fun enqueue(text: String) {
        if (metrics.degradedMode) {
            metrics.recordDropped()
            return
        }
        pending.incrementAndGet()
        if (!queue.offer(text)) {
            pending.decrementAndGet()
            metrics.recordDropped()
            maybeLogDrop()
        }
    }

    /**
     * Block until the queue is empty and the worker is idle, or [timeoutMs] elapses.
     * Used by tests; production code does not need this.
     */
    fun flush(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pending.get() == 0) return
            Thread.sleep(5)
        }
    }

    override fun close() {
        running.set(false)
        thread.interrupt()
        thread.join(2_000L)
    }

    private fun runLoop() {
        while (running.get()) {
            val text =
                try {
                    queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            try {
                deliver(text)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun deliver(text: String) {
        var attempt = 0
        while (true) {
            val outcome =
                try {
                    send(text)
                } catch (t: Throwable) {
                    log.warn("[notify] send threw, treating as transient", t)
                    Outcome.NetworkError(t.message ?: t::class.java.simpleName)
                }
            when (outcome) {
                Outcome.Ok -> {
                    metrics.recordSent()
                    return
                }
                Outcome.AuthFailed -> {
                    metrics.flipDegraded()
                    metrics.recordFailed()
                    log.error("[notify] Telegram notifications disabled until restart — auth/chat invalid")
                    return
                }
                is Outcome.RateLimited -> {
                    metrics.recordRateLimit()
                    log.warn("[notify] rate-limited, sleeping {} ms", outcome.retryAfterMs)
                    sleepIgnoringInterrupt(outcome.retryAfterMs)
                    // do NOT consume a backoff slot — try again
                }
                is Outcome.BadRequest -> {
                    metrics.recordFailed()
                    log.error("[notify] Telegram rejected request (code={}, body={})", outcome.code, outcome.body)
                    return
                }
                is Outcome.TransientError, is Outcome.NetworkError -> {
                    if (attempt >= backoffMs.size) {
                        metrics.recordFailed()
                        log.error("[notify] giving up on message after {} retries", attempt)
                        return
                    }
                    val delay = backoffMs[attempt]
                    log.warn("[notify] transient failure (attempt {}), sleeping {} ms", attempt + 1, delay)
                    sleepIgnoringInterrupt(delay)
                    attempt++
                }
            }
        }
    }

    private fun sleepIgnoringInterrupt(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun maybeLogDrop() {
        val now = System.currentTimeMillis()
        if (now - lastDropLogMs > 60_000L) {
            log.warn("[notify] queue full, dropping messages")
            lastDropLogMs = now
        }
    }
}
