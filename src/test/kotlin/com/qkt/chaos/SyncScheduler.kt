package com.qkt.chaos

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A synchronous [ScheduledExecutorService] for deterministic tests: it records the delays it was
 * asked to schedule and runs queued tasks only when [runNext] is called — no wall-clock waiting.
 *
 * e.g. schedule a reconnect attempt, then `runNext()` to execute it immediately and inspect
 * [scheduledDelays] to assert the backoff sequence.
 */
class SyncScheduler {
    val scheduledDelays: MutableList<Long> = mutableListOf()
    private val tasks: MutableList<Pair<Long, Runnable>> = mutableListOf()

    var aborted: Boolean = false
        private set

    val pending: Int get() = tasks.size

    fun runNext() {
        val (_, task) = tasks.removeAt(0)
        task.run()
    }

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
}
