package com.qkt.connector.gateway

import com.qkt.common.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Runs a gateway account's resynchronizations: [reconcile] now, and when it fails the resynchronization
 * is owed, reported to [alert] with the consecutive failures, and tried again by [retryOwed] at most every
 * [retryMs]: on later events, and on its own timer, so a quiet stream does not leave it owed. One runs at a
 * time. A resynchronization caused by the stream first rechecks the gateway's identity through
 * [checkIdentity].
 */
internal class GatewayResyncer(
    private val clock: Clock,
    private val reconcile: (String) -> Unit,
    private val checkIdentity: () -> Unit,
    private val alert: (Int) -> Unit,
    private val retryMs: Long = 5_000L,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(GatewayResyncer::class.java)
    private val timer =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gateway-resync").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching(::retryOwed) }, retryMs, retryMs, TimeUnit.MILLISECONDS)
        }

    @Volatile private var owed: String? = null

    @Volatile private var failures = 0

    @Volatile private var attemptAt = 0L

    /** Reconciles for [reason]; a failure is owed, never lost. */
    @Synchronized
    fun resync(reason: String) {
        attemptAt = clock.now()
        try {
            if (reason.startsWith("stream")) checkIdentity()
            reconcile(reason)
            owed = null
            failures = 0
        } catch (e: RuntimeException) {
            log.error("gateway resync ({}) owed: {}", reason, e.message)
            owed = reason
            failures++
            alert(failures)
        }
    }

    /** Tries an owed resynchronization again, unless one was tried within [retryMs]. */
    fun retryOwed() {
        val reason = owed ?: return
        if (clock.now() - attemptAt >= retryMs) resync("owed: $reason")
    }

    /** Stops the retry timer. */
    override fun close() {
        timer.shutdownNow()
    }
}
