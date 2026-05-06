package com.qkt.common.net

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    private class TestScheduler {
        val fixedRateInitialDelays: MutableList<Long> = mutableListOf()
        val fixedRatePeriods: MutableList<Long> = mutableListOf()
        var cancelled: Boolean = false
        private var task: Runnable? = null

        fun fireTick() {
            task?.run() ?: error("no task scheduled")
        }

        fun asExecutor(): ScheduledExecutorService =
            object : ScheduledExecutorService {
                override fun scheduleAtFixedRate(
                    command: Runnable,
                    initialDelay: Long,
                    period: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> {
                    fixedRateInitialDelays.add(unit.toMillis(initialDelay))
                    fixedRatePeriods.add(unit.toMillis(period))
                    task = command
                    return CapturingFuture { cancelled = true }
                }

                override fun shutdown() {}

                override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

                override fun isShutdown(): Boolean = false

                override fun isTerminated(): Boolean = false

                override fun awaitTermination(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = true

                override fun <T> submit(task: Callable<T>): Future<T> = error("not used")

                override fun <T> submit(
                    task: Runnable,
                    result: T,
                ): Future<T> = error("not used")

                override fun submit(task: Runnable): Future<*> = error("not used")

                override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
                    mutableListOf()

                override fun <T> invokeAll(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): MutableList<Future<T>> = mutableListOf()

                override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T = error("not used")

                override fun <T> invokeAny(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): T = error("not used")

                override fun execute(command: Runnable) {}

                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")

                override fun <V> schedule(
                    callable: Callable<V>,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<V> = error("not used")

                override fun scheduleWithFixedDelay(
                    command: Runnable,
                    initialDelay: Long,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")
            }
    }

    private class CapturingFuture(
        private val onCancel: () -> Unit,
    ) : ScheduledFuture<Any?> {
        override fun compareTo(other: Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = 0L

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            onCancel()
            return true
        }

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null
    }
}
