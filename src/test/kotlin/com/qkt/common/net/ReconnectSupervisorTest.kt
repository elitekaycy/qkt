package com.qkt.common.net

import com.qkt.chaos.SyncScheduler
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconnectSupervisorTest {
    @Test
    fun `scheduleReconnect schedules with backoff delay`() {
        val scheduler = SyncScheduler()
        val attempts = AtomicInteger()
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = {
                    attempts.incrementAndGet()
                    false
                },
                executor = scheduler.asExecutor(),
            )

        supervisor.scheduleReconnect()

        assertThat(scheduler.scheduledDelays).containsExactly(1_000L)
    }

    @Test
    fun `failed attempt schedules next with increased backoff`() {
        val scheduler = SyncScheduler()
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = { false },
                executor = scheduler.asExecutor(),
            )

        supervisor.scheduleReconnect()
        scheduler.runNext()
        scheduler.runNext()

        assertThat(scheduler.scheduledDelays).containsExactly(1_000L, 2_000L, 4_000L)
    }

    @Test
    fun `successful attempt resets attempt counter and fires onReconnected`() {
        val scheduler = SyncScheduler()
        var reconnects = 0
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = { true },
                onReconnected = { reconnects++ },
                executor = scheduler.asExecutor(),
            )

        supervisor.scheduleReconnect()
        scheduler.runNext()

        assertThat(reconnects).isEqualTo(1)
        supervisor.scheduleReconnect()
        assertThat(scheduler.scheduledDelays.last()).isEqualTo(1_000L)
    }

    @Test
    fun `isReconnecting reflects in-flight retry state`() {
        val scheduler = SyncScheduler()
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = { false },
                executor = scheduler.asExecutor(),
            )

        assertThat(supervisor.isReconnecting).isFalse()
        supervisor.scheduleReconnect()
        assertThat(supervisor.isReconnecting).isTrue()
    }

    @Test
    fun `abort cancels pending retries`() {
        val scheduler = SyncScheduler()
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = { false },
                executor = scheduler.asExecutor(),
            )

        supervisor.scheduleReconnect()
        supervisor.abort()
        assertThat(scheduler.aborted).isTrue()
        assertThat(supervisor.isReconnecting).isFalse()
    }

    @Test
    fun `successful reconnect followed by another disconnect uses fresh backoff`() {
        val scheduler = SyncScheduler()
        var attemptResult = false
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(initialMs = 1_000L, capMs = 60_000L),
                attemptReconnect = { attemptResult },
                executor = scheduler.asExecutor(),
            )

        supervisor.scheduleReconnect()
        scheduler.runNext()
        scheduler.runNext()
        attemptResult = true
        scheduler.runNext()

        attemptResult = false
        supervisor.scheduleReconnect()

        assertThat(scheduler.scheduledDelays.takeLast(1)).containsExactly(1_000L)
    }
}
