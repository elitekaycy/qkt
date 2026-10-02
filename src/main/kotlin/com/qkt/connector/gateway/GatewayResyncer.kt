package com.qkt.connector.gateway

import com.qkt.common.Clock
import org.slf4j.LoggerFactory

/**
 * Runs a gateway account's resynchronizations: [reconcile] now, and when it fails the resynchronization
 * is owed, reported to [alert] with the consecutive failures, and tried again by [retryOwed] (called on
 * later events) at most every [retryMs]. A resynchronization caused by the stream first rechecks the
 * gateway's identity through [checkIdentity].
 */
internal class GatewayResyncer(
    private val clock: Clock,
    private val reconcile: (String) -> Unit,
    private val checkIdentity: () -> Unit,
    private val alert: (Int) -> Unit,
    private val retryMs: Long = 5_000L,
) {
    private val log = LoggerFactory.getLogger(GatewayResyncer::class.java)

    @Volatile private var owed: String? = null

    @Volatile private var failures = 0

    @Volatile private var attemptAt = 0L

    /** Reconciles for [reason]; a failure is owed, never lost. */
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
}
