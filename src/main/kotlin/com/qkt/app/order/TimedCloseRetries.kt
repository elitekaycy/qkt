package com.qkt.app.order

import com.qkt.app.UnfilledExitAlerts
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import com.qkt.persistence.PersistedTimeExit
import org.slf4j.Logger

/**
 * Fires a due `EXIT AFTER` exit and keeps it armed until its close fills (#1360). Before, the exit
 * was dropped the moment its close was sent, so a close the venue cancelled or rejected unfilled
 * (a thin Deribit book: the order rests at the price band and the gateway cancels it) left the leg
 * open for good. Now a due exit whose last close ended unfilled sends a new close, for only what
 * is still open, and is re-armed [RETRY_MS] later — about one bar of a 1m strategy, since a timed exit
 * has no bar of its own; a close still working is waited for; a filled close ends the exit. The
 * [UnfilledExitAlerts.alertsAt]th failure in a row raises the operator alert. The state rides in the
 * persisted exit, so a restart resumes it. The last close stays in the order book while its exit is armed, so a
 * refusal forgotten by the next tick still counts (#1643), and each alert names the venue's refusal. A refusal no
 * resend can change ([isPermanent]: a malformed request, an order the venue cannot take) ends the exit at once with
 * an `EXIT FAILED` alert instead of a new close every minute for ever.
 */
internal class TimedCloseRetries(
    private val book: OrderBook,
    private val closer: TimedLegCloser,
    private val ops: OrderOps,
    private val log: Logger?,
    private val armed: TimedExitBook,
) {
    // The venue's refusal of each armed exit's last close, until that close is judged.
    private val refusals = HashMap<String, String>()

    // The close being sent: a refusal before the send returns (qkt's own) has no armed exit to find yet.
    private var sending: String? = null

    /** Act on [exit], due at [now]; the exit to re-arm, or null when it is done. */
    fun fire(
        exit: PersistedTimeExit,
        now: Long,
    ): PersistedTimeExit? {
        // A close still working for the leg (this exit's own, a flatten's, a CLOSE) is waited for (#1638).
        if (closer.covered(exit)) return exit.copy(deadlineMs = now + RETRY_MS)
        val last = exit.closeId?.let { book[it] }
        var failures = exit.failures
        if (last != null) {
            if (!last.state.isTerminal) return exit.copy(deadlineMs = now + RETRY_MS)
            val refusal = refusals.remove(last.id)
            if (last.state == OrderState.FILLED) return null
            failures++
            onFailed(exit, refusal?.let { "${last.state}: $it" } ?: last.state.toString(), failures)
        }
        val closeId = if (exit.attempts == 0) "${exit.id}-close" else "${exit.id}-close-r${exit.attempts}"
        sending = closeId
        val sent =
            try {
                closer.close(exit, closeId)
            } finally {
                sending = null
            }
        if (!sent) return null
        val next =
            exit.copy(closeId = closeId, attempts = exit.attempts + 1, failures = failures, deadlineMs = now + RETRY_MS)
        val refusal = refusals[closeId]
        if (refusal == null || !isPermanent(refusal)) return next
        refusals.remove(closeId)
        fail(next, refusal)
        return null
    }

    private fun onFailed(
        exit: PersistedTimeExit,
        state: String,
        failures: Int,
    ) {
        val open = closer.openQuantity(exit)?.stripTrailingZeros()?.toPlainString() ?: "0"
        log?.warn(
            "timed exit {} close {} ended {} with {} {} still open: resending (consecutive failures {})",
            exit.id,
            exit.closeId,
            state,
            open,
            exit.symbol,
            failures,
        )
        if (!UnfilledExitAlerts.alertsAt(failures)) return
        ops.reportProtectionFailure(
            exit.strategyId,
            "EXIT NOT FILLED: ${exit.strategyId} timed exit ${exit.id} could not close ${exit.symbol} after " +
                "$failures consecutive attempts (last ended $state); position $open still open. qkt keeps " +
                "retrying every ${RETRY_MS / 1000}s.",
        )
    }

    /**
     * The venue refused order [closeId] for [reason]. When it is an armed exit's last close and the refusal is one
     * no resend can change, the exit is dropped and the operator alerted now; any other refusal of a close is kept
     * for the retry's log and alert.
     */
    fun refused(
        closeId: String,
        reason: String,
    ) {
        if (closeId == sending) {
            refusals[closeId] = reason
            return
        }
        if (armed.isEmpty()) return
        val exit = armed.values.firstOrNull { it.closeId == closeId } ?: return
        if (!isPermanent(reason)) {
            refusals[closeId] = reason
            return
        }
        armed.remove(exit.id)
        fail(exit, reason)
        ops.persistAll()
    }

    private fun fail(
        exit: PersistedTimeExit,
        reason: String,
    ) {
        val open = closer.openQuantity(exit)?.stripTrailingZeros()?.toPlainString() ?: "0"
        val message =
            "EXIT FAILED: ${exit.strategyId} timed exit ${exit.id} could not close ${exit.symbol}: the venue refused " +
                "close ${exit.closeId} for a reason no resend changes ($reason); position $open still open. qkt " +
                "will not send it again: close the position by hand."
        log?.error(message)
        ops.reportProtectionFailure(exit.strategyId, message)
    }

    companion object {
        // Gateway refusals that say the order itself can never be taken: VGP's `invalid_request` and `unsupported`,
        // and qkt's own refusal of an order it cannot express for the venue (`unsupported: `, GatewayBroker).
        private val PERMANENT = listOf("invalid_request:", "unsupported:")

        /** Whether refusal [reason] would come back the same for every resend of the close. */
        fun isPermanent(reason: String): Boolean = PERMANENT.any { reason.startsWith(it) }

        /** How long after sending a timed close it is checked, and resent if the venue ended it unfilled. */
        const val RETRY_MS = 60_000L
    }
}
