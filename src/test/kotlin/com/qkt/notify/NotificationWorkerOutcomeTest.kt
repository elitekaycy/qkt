package com.qkt.notify

import com.qkt.notify.TelegramClient.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationWorkerOutcomeTest {
    @Test
    fun `a send that throws is retried as a transient failure`() {
        val attempts = AtomicInteger(0)
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ ->
                    if (attempts.incrementAndGet() ==
                        1
                    ) {
                        throw IllegalStateException("socket")
                    } else {
                        Outcome.Ok
                    }
                },
                metrics = metrics,
                backoffMs = listOf(1L),
            )
        w.enqueue("x")
        w.flush(timeoutMs = 2_000L)
        w.close()

        assertThat(attempts.get()).isEqualTo(2)
        assertThat(metrics.sent).isEqualTo(1L)
        assertThat(metrics.failed).isZero
    }

    @Test
    fun `a rejected request fails once without retrying`() {
        val attempts = AtomicInteger(0)
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ ->
                    attempts.incrementAndGet()
                    Outcome.BadRequest(400, "chat not found")
                },
                metrics = metrics,
                backoffMs = listOf(1L, 1L),
            )
        w.enqueue("x")
        w.flush(timeoutMs = 2_000L)
        w.close()

        assertThat(attempts.get()).isEqualTo(1)
        assertThat(metrics.failed).isEqualTo(1L)
        assertThat(metrics.sent).isZero
    }

    @Test
    fun `closing during a retry backoff cuts the wait short and gives up`() {
        val firstAttempt = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        val metrics = AtomicNotifierMetrics()
        val w =
            NotificationWorker(
                send = { _ ->
                    attempts.incrementAndGet()
                    firstAttempt.countDown()
                    Outcome.NetworkError("down")
                },
                metrics = metrics,
                backoffMs = listOf(60_000L),
            )
        w.enqueue("x")
        assertThat(firstAttempt.await(5, TimeUnit.SECONDS)).isTrue
        w.close()

        assertThat(attempts.get()).isEqualTo(2)
        assertThat(metrics.failed).isEqualTo(1L)
    }
}
