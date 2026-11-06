package com.qkt.notify

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class DailySummaryStartTest {
    /** Captures the scheduled task instead of running it, so the test drives each fire. */
    private class CapturingExecutor(
        private val real: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    ) : ScheduledExecutorService by real {
        var task: Runnable? = null
        var initialDelay = -1L
        var period = -1L

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            task = command
            this.initialDelay = unit.toMillis(initialDelay)
            this.period = unit.toMillis(period)
            return real.schedule({}, 0L, TimeUnit.MILLISECONDS)
        }
    }

    private val summary = NotificationEvent.DailySummary(asOfUtc = "2026-05-17", strategies = emptyList(), timestamp = 1L)

    private class Recording : Notifier {
        val seen = mutableListOf<NotificationEvent>()

        override fun notify(event: NotificationEvent) {
            seen += event
        }

        override fun close() = Unit
    }

    @Test
    fun `starting at a utc time schedules the first fire within the next day`() {
        val executor = CapturingExecutor()
        val notifier = Recording()
        DailySummaryScheduler(notifier, { summary }, executor = executor).use { it.startAtUtc("06:30") }

        assertThat(executor.initialDelay).isBetween(1L, TimeUnit.DAYS.toMillis(1))
        assertThat(executor.period).isEqualTo(TimeUnit.DAYS.toMillis(1))
        executor.task!!.run()
        assertThat(notifier.seen).containsExactly(summary)
        assertThat(notifier.metrics).isNull()
    }

    @Test
    fun `a failing summary producer is logged and does not kill the schedule`() {
        val executor = CapturingExecutor()
        val notifier = Recording()
        DailySummaryScheduler(notifier, { error("no data") }, periodMs = 10L, executor = executor)
            .use { it.start(initialDelayMs = 5L) }

        assertThat(executor.initialDelay).isEqualTo(5L)
        assertThatCode { executor.task!!.run() }.doesNotThrowAnyException()
        assertThat(notifier.seen).isEmpty()
    }
}
