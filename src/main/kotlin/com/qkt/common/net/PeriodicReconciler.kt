package com.qkt.common.net

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class PeriodicReconciler(
    private val intervalMs: Long,
    private val action: () -> Unit,
    private val executor: ScheduledExecutorService = defaultExecutor(),
    private val onError: (Throwable) -> Unit = { log.warn("periodic reconcile failed", it) },
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        future =
            executor.scheduleAtFixedRate(
                ::tick,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS,
            )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        future?.cancel(false)
        future = null
    }

    val isRunning: Boolean
        get() = started.get()

    private fun tick() {
        try {
            action()
        } catch (e: Throwable) {
            onError(e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PeriodicReconciler::class.java)

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "qkt-periodic-reconciler").apply { isDaemon = true }
            }
    }
}
