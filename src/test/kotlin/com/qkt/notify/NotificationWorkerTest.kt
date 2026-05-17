package com.qkt.notify

import com.qkt.notify.TelegramClient.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationWorkerTest {
    @Test
    fun `drains queue in FIFO order and sends each`() {
        val sent = mutableListOf<String>()
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { text ->
                    synchronized(sent) { sent.add(text) }
                    Outcome.Ok
                },
                metrics = metrics,
                backoffMs = listOf(1L, 1L, 1L),
            )
        w.enqueue("a")
        w.enqueue("b")
        w.enqueue("c")

        w.flush(timeoutMs = 1_000L)
        assertThat(sent).containsExactly("a", "b", "c")
        assertThat(metrics.sent).isEqualTo(3L)
        w.close()
    }

    @Test
    fun `retries on TransientError up to backoff length then drops and increments failed`() {
        var attempts = 0
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ ->
                    attempts++
                    Outcome.TransientError(500)
                },
                metrics = metrics,
                backoffMs = listOf(1L, 1L, 1L),
            )
        w.enqueue("x")
        w.flush(timeoutMs = 1_000L)
        // backoffMs has 3 entries (delays between retries), so 1 initial + 3 retries = 4 attempts.
        assertThat(attempts).isEqualTo(4)
        assertThat(metrics.failed).isEqualTo(1L)
        assertThat(metrics.sent).isZero()
        w.close()
    }

    @Test
    fun `honors RateLimited delay then retries`() {
        var firstSeen = false
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ ->
                    if (!firstSeen) {
                        firstSeen = true
                        Outcome.RateLimited(retryAfterMs = 5L)
                    } else {
                        Outcome.Ok
                    }
                },
                metrics = metrics,
                backoffMs = listOf(1L, 1L),
            )
        w.enqueue("x")
        w.flush(timeoutMs = 1_000L)
        assertThat(metrics.sent).isEqualTo(1L)
        assertThat(metrics.rateLimitHits).isEqualTo(1L)
        w.close()
    }

    @Test
    fun `AuthFailed flips degraded mode and subsequent enqueues are dropped`() {
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ -> Outcome.AuthFailed },
                metrics = metrics,
                backoffMs = listOf(1L),
            )
        w.enqueue("x")
        w.flush(timeoutMs = 1_000L)
        assertThat(metrics.degradedMode).isTrue()
        assertThat(metrics.failed).isEqualTo(1L)

        w.enqueue("y")
        w.flush(timeoutMs = 1_000L)
        assertThat(metrics.dropped).isEqualTo(1L) // y was dropped, never sent
        w.close()
    }

    @Test
    fun `queue full drops new messages and increments dropped`() {
        val latch = CountDownLatch(1)
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ ->
                    latch.await(2, TimeUnit.SECONDS)
                    Outcome.Ok
                },
                metrics = metrics,
                queueCapacity = 2,
                backoffMs = listOf(1L),
            )
        // first enqueue: worker picks up immediately, blocks in send.
        w.enqueue("a")
        Thread.sleep(50) // give worker time to take "a" off the queue
        w.enqueue("b")
        w.enqueue("c")
        w.enqueue("d") // queue full -> dropped
        latch.countDown()
        w.flush(timeoutMs = 2_000L)

        assertThat(metrics.dropped).isEqualTo(1L)
        assertThat(metrics.sent).isEqualTo(3L) // a, b, c
        w.close()
    }
}
