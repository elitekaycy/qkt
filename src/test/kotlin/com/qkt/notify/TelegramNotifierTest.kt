package com.qkt.notify

import com.qkt.notify.TelegramClient.Outcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramNotifierTest {
    @Test
    fun `notify formats event and enqueues to worker`() {
        val captured = mutableListOf<String>()
        val metrics = AtomicNotifierMetrics()
        val worker =
            NotificationWorker(
                send = { text ->
                    captured.add(text)
                    Outcome.Ok
                },
                metrics = metrics,
                backoffMs = listOf(1L),
            )
        val n = TelegramNotifier(worker = worker, metrics = metrics)
        n.notify(NotificationEvent.Resumed(strategyId = "x", timestamp = 1L))
        worker.flush(timeoutMs = 1_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).contains("[INFO] qkt resumed x")
        n.close()
    }

    @Test
    fun `close shuts down the worker`() {
        val metrics = AtomicNotifierMetrics()
        val worker =
            NotificationWorker(
                send = { Outcome.Ok },
                metrics = metrics,
                backoffMs = listOf(1L),
            )
        val n = TelegramNotifier(worker = worker, metrics = metrics)
        n.close() // must not throw, worker thread joins within its own timeout
    }
}
