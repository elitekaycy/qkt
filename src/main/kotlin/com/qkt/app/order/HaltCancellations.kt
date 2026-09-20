package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.execution.isTerminal

/**
 * Cancels issued by a risk halt, retried with backoff until the broker confirms a terminal
 * state. A halt that leaves an entry live at the venue is a halt that did not happen, so
 * unconfirmed cancels are re-sent and escalated to an operator alert after a few attempts.
 */
internal class HaltCancellations(
    private val book: OrderBook,
    private val broker: Broker,
    private val clock: Clock,
    private val alert: (strategyId: String, message: String) -> Unit,
) {
    private class Attempt(
        var attempts: Int,
        var nextAttemptAtMs: Long,
        var alerted: Boolean = false,
    )

    private val attempts: MutableMap<String, Attempt> = mutableMapOf()

    /** Starts tracking a halt-issued cancel of [id]; the caller sends the first cancel. */
    fun begin(id: String) {
        attempts[id] = Attempt(attempts = 1, nextAttemptAtMs = clock.now() + RETRY_MS)
    }

    /** Stops tracking [id]; the broker reported a terminal outcome for it. */
    fun forget(id: String) {
        attempts.remove(id)
    }

    /** Re-sends every cancel whose retry time has come, escalating long-unconfirmed ones. */
    fun retry(nowMs: Long) {
        val due = attempts.filterValues { nowMs >= it.nextAttemptAtMs }.keys.toList()
        for (id in due) {
            val managed = book[id]
            if (managed == null || managed.state.isTerminal) {
                attempts.remove(id)
                continue
            }
            val state = attempts[id] ?: continue
            state.attempts += 1
            state.nextAttemptAtMs = nowMs + delayMs(state.attempts)
            if (state.attempts >= ALERT_ATTEMPTS && !state.alerted) {
                state.alerted = true
                alert(
                    managed.request.strategyId,
                    "CRITICAL halt cancellation remains unconfirmed for ${managed.id} " +
                        "after ${state.attempts} attempts",
                )
            }
            broker.cancel(id)
        }
    }

    /** A cancel was refused: retry it no later than one base interval from now. */
    fun onCancelFailed(id: String) {
        val state = attempts[id] ?: return
        state.nextAttemptAtMs = minOf(state.nextAttemptAtMs, clock.now() + RETRY_MS)
    }

    private fun delayMs(attempts: Int): Long =
        (RETRY_MS * (1L shl (attempts - 1).coerceAtMost(5))).coerceAtMost(MAX_RETRY_MS)

    private companion object {
        const val RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
        const val ALERT_ATTEMPTS = 3
    }
}
