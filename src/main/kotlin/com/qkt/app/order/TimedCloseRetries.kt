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
 * persisted exit, so a restart resumes it.
 */
internal class TimedCloseRetries(
    private val book: OrderBook,
    private val closer: TimedLegCloser,
    private val ops: OrderOps,
    private val log: Logger?,
) {
    /** Act on [exit], due at [now]; the exit to re-arm, or null when it is done. */
    fun fire(
        exit: PersistedTimeExit,
        now: Long,
    ): PersistedTimeExit? {
        val last = exit.closeId?.let { book[it] }
        var failures = exit.failures
        if (last != null) {
            if (!last.state.isTerminal) return exit.copy(deadlineMs = now + RETRY_MS)
            if (last.state == OrderState.FILLED) return null
            failures++
            onFailed(exit, last.state, failures)
        }
        val closeId = if (exit.attempts == 0) "${exit.id}-close" else "${exit.id}-close-r${exit.attempts}"
        if (!closer.close(exit, closeId)) return null
        return exit.copy(
            closeId = closeId,
            attempts = exit.attempts + 1,
            failures = failures,
            deadlineMs =
                now + RETRY_MS,
        )
    }

    private fun onFailed(
        exit: PersistedTimeExit,
        state: OrderState,
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

    companion object {
        /** How long after sending a timed close it is checked, and resent if the venue ended it unfilled. */
        const val RETRY_MS = 60_000L
    }
}
