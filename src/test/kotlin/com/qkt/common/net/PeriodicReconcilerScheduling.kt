package com.qkt.common.net

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A hand-driven scheduled executor and the futures it hands out, so reconciler scheduling is observable without
 * time passing.
 */
internal object PeriodicReconcilerScheduling {
    class TestScheduler {
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

    class CapturingFuture(
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
