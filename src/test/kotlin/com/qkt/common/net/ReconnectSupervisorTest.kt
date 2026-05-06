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

class ReconnectSupervisorTest {
    /** Hand-rolled scheduler: records delays, runs tasks synchronously when `runNext()` is called. */
    private class TestScheduler {
        val scheduledDelays: MutableList<Long> = mutableListOf()
        private val tasks: MutableList<Pair<Long, Runnable>> = mutableListOf()
        var aborted: Boolean = false

        fun asExecutor(): ScheduledExecutorService =
            object : ScheduledExecutorService {
                override fun shutdown() {}

                override fun shutdownNow(): MutableList<Runnable> {
                    aborted = true
                    return mutableListOf()
                }

                override fun isShutdown(): Boolean = aborted

                override fun isTerminated(): Boolean = aborted

                override fun awaitTermination(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = true

                override fun <T> submit(task: Callable<T>): Future<T> = NoOpFuture.cast()

                override fun <T> submit(
                    task: Runnable,
                    result: T,
                ): Future<T> = NoOpFuture.cast()

                override fun submit(task: Runnable): Future<*> = NoOpFuture

                override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> = mutableListOf()

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
                ): ScheduledFuture<*> {
                    scheduledDelays.add(unit.toMillis(delay))
                    tasks.add(unit.toMillis(delay) to command)
                    return NoOpFuture
                }

                override fun <V> schedule(
                    callable: Callable<V>,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<V> = NoOpFuture.cast()

                override fun scheduleAtFixedRate(
                    command: Runnable,
                    initialDelay: Long,
                    period: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = NoOpFuture

                override fun scheduleWithFixedDelay(
                    command: Runnable,
                    initialDelay: Long,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = NoOpFuture
            }

        fun runNext() {
            val (_, task) = tasks.removeAt(0)
            task.run()
        }
    }

    private object NoOpFuture : ScheduledFuture<Any?> {
        override fun compareTo(other: Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = 0L

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null

        @Suppress("UNCHECKED_CAST")
        fun <T> cast(): ScheduledFuture<T> = this as ScheduledFuture<T>
    }

    @Test
    fun `scheduleReconnect schedules with backoff delay`() {
        val scheduler = TestScheduler()
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
        val scheduler = TestScheduler()
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
        val scheduler = TestScheduler()
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
        val scheduler = TestScheduler()
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
        val scheduler = TestScheduler()
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
        val scheduler = TestScheduler()
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
