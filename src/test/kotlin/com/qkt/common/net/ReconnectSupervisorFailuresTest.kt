package com.qkt.common.net

import com.qkt.chaos.SyncScheduler
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconnectSupervisorFailuresTest {
    private val fixed =
        object : BackoffPolicy {
            override fun nextDelayMs(attempt: Int): Long = 10L * attempt
        }

    @Test
    fun `an attempt that throws counts as failed and schedules the next`() {
        val scheduler = SyncScheduler()
        val attempts = AtomicInteger()
        val reconnected = AtomicInteger()
        val supervisor =
            ReconnectSupervisor(
                backoff = fixed,
                attemptReconnect = { if (attempts.incrementAndGet() == 1) error("socket closed") else true },
                onReconnected = {
                    reconnected.incrementAndGet()
                    error("listener broke")
                },
                executor = scheduler.asExecutor(),
            )

        supervisor.scheduleReconnect()
        scheduler.runNext()
        assertThat(supervisor.isReconnecting).isTrue
        scheduler.runNext()

        assertThat(scheduler.scheduledDelays).containsExactly(10L, 20L)
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(reconnected.get()).isEqualTo(1)
        assertThat(supervisor.isReconnecting).isFalse
    }

    @Test
    fun `the default executor runs on a daemon thread until aborted`() {
        val hourly =
            object : BackoffPolicy {
                override fun nextDelayMs(attempt: Int): Long = 3_600_000L
            }
        val supervisor = ReconnectSupervisor(backoff = hourly, attemptReconnect = { true })

        supervisor.scheduleReconnect()
        assertThat(supervisor.isReconnecting).isTrue
        supervisor.abort()
        assertThat(supervisor.isReconnecting).isFalse
    }

    @Test
    fun `the default reconciler executor schedules until stopped`() {
        val reconciler = PeriodicReconciler(intervalMs = 3_600_000L, action = {})

        reconciler.start()
        assertThat(reconciler.isRunning).isTrue
        reconciler.stop()
        assertThat(reconciler.isRunning).isFalse
    }
}
