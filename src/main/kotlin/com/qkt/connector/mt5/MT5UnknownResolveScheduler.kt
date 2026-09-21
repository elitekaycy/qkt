package com.qkt.connector.mt5

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs the slow venue lookups that settle an order whose outcome is unknown, off the HTTP callback
 * thread that discovered it, e.g. a placement that timed out is looked up now, and if the venue
 * still cannot be read, again [periodicResolveMs] later. Work offered after [shutdownNow] is dropped
 * quietly; a rejection from a live executor still throws.
 */
internal class MT5UnknownResolveScheduler(
    profileName: String,
    private val periodicResolveMs: Long,
) {
    private val unknownResolveExecutor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "qkt-mt5-unknown-resolve-$profileName").apply { isDaemon = true }
        }

    fun executeUnknownResolution(task: () -> Unit) {
        try {
            unknownResolveExecutor.execute(task)
        } catch (failure: RejectedExecutionException) {
            if (!unknownResolveExecutor.isShutdown) throw failure
        }
    }

    fun scheduleUnknownResolution(task: () -> Unit) {
        try {
            unknownResolveExecutor.schedule(
                task,
                periodicResolveMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: RejectedExecutionException) {
            if (!unknownResolveExecutor.isShutdown) throw failure
        }
    }

    fun shutdownNow() {
        unknownResolveExecutor.shutdownNow()
    }
}
