package com.qkt.common.net

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

class ReconnectSupervisor(
    private val backoff: BackoffPolicy = ExponentialBackoff(),
    private val attemptReconnect: () -> Boolean,
    private val onReconnected: () -> Unit = {},
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "reconnect-supervisor").apply { isDaemon = true }
        },
) {
    private val log = LoggerFactory.getLogger(ReconnectSupervisor::class.java)

    private val attemptCount: AtomicInteger = AtomicInteger(0)
    private val reconnecting: AtomicBoolean = AtomicBoolean(false)

    val isReconnecting: Boolean get() = reconnecting.get()

    fun scheduleReconnect() {
        reconnecting.set(true)
        val attempt = attemptCount.incrementAndGet()
        val delay = backoff.nextDelayMs(attempt)
        log.info("Scheduling reconnect attempt {} in {}ms", attempt, delay)
        executor.schedule({ runAttempt() }, delay, TimeUnit.MILLISECONDS)
    }

    fun abort() {
        reconnecting.set(false)
        attemptCount.set(0)
        executor.shutdownNow()
    }

    private fun runAttempt() {
        val success =
            try {
                attemptReconnect()
            } catch (e: Exception) {
                log.warn("Reconnect attempt threw: {}", e.message)
                false
            }
        if (success) {
            log.info("Reconnect attempt {} succeeded", attemptCount.get())
            attemptCount.set(0)
            reconnecting.set(false)
            try {
                onReconnected()
            } catch (e: Exception) {
                log.warn("onReconnected callback threw: {}", e.message)
            }
        } else {
            log.warn("Reconnect attempt {} failed; scheduling next", attemptCount.get())
            scheduleReconnect()
        }
    }
}
