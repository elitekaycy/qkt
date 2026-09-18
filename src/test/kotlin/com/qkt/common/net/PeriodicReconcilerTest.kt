package com.qkt.common.net

import com.qkt.common.net.PeriodicReconcilerScheduling.TestScheduler
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PeriodicReconcilerTest {
    @Test
    fun `start schedules action at fixed rate with given interval`() {
        val scheduler = TestScheduler()
        val invocations = AtomicInteger()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { invocations.incrementAndGet() },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()

        assertThat(scheduler.fixedRateInitialDelays).containsExactly(30_000L)
        assertThat(scheduler.fixedRatePeriods).containsExactly(30_000L)
        assertThat(reconciler.isRunning).isTrue
    }

    @Test
    fun `tick invokes action`() {
        val scheduler = TestScheduler()
        val invocations = AtomicInteger()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { invocations.incrementAndGet() },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()
        scheduler.fireTick()
        scheduler.fireTick()
        scheduler.fireTick()

        assertThat(invocations.get()).isEqualTo(3)
    }

    @Test
    fun `tick swallows exceptions and keeps loop alive`() {
        val scheduler = TestScheduler()
        val invocations = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = {
                    val n = invocations.incrementAndGet()
                    if (n == 2) error("boom")
                },
                executor = scheduler.asExecutor(),
                onError = { errors.add(it) },
            )

        reconciler.start()
        scheduler.fireTick()
        scheduler.fireTick()
        scheduler.fireTick()

        assertThat(invocations.get()).isEqualTo(3)
        assertThat(errors).hasSize(1)
        assertThat(errors.single().message).isEqualTo("boom")
    }

    @Test
    fun `start is idempotent`() {
        val scheduler = TestScheduler()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()
        reconciler.start()
        reconciler.start()

        assertThat(scheduler.fixedRateInitialDelays).hasSize(1)
    }

    @Test
    fun `stop cancels future and flips isRunning`() {
        val scheduler = TestScheduler()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()
        reconciler.stop()

        assertThat(reconciler.isRunning).isFalse
        assertThat(scheduler.cancelled).isTrue
    }

    @Test
    fun `stop without prior start is a noop`() {
        val scheduler = TestScheduler()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { },
                executor = scheduler.asExecutor(),
            )

        reconciler.stop()

        assertThat(reconciler.isRunning).isFalse
        assertThat(scheduler.cancelled).isFalse
    }
}
