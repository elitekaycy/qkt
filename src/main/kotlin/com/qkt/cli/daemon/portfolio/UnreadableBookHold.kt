package com.qkt.cli.daemon.portfolio

import com.qkt.common.Clock
import com.qkt.risk.HaltScope
import org.slf4j.LoggerFactory

/**
 * The hold a portfolio book puts on its children while it cannot read them (#1640): each child refuses new entries
 * under a [HaltScope.TRANSIENT] halt, keeps its positions and their protective exits, and is released by the first
 * evaluation that reads the book again. A failed read says the engine is busy or faulted, not that the book lost
 * money, so it never latches across a restart: the restarted book reads its persisted peaks and realized PnL, and a
 * limit that really tripped halts it on that first read.
 */
internal class UnreadableBookHold(
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(UnreadableBookHold::class.java)
    private val held = mutableSetOf<ChildRiskTarget>()

    @Volatile
    private var reason: String? = null

    @Volatile
    private var sinceMs = 0L

    /** The hold as an operator sees it, or null when the book reads. */
    fun view(): BookHalt? = reason?.let { BookHalt(it, HaltScope.TRANSIENT, sinceMs) }

    /** Holds every child not already halted; the first [failure] of a run names the hold until it is released. */
    fun hold(
        failure: String,
        children: List<ChildRiskTarget>,
    ) {
        val active =
            reason ?: failure.also {
                reason = it
                sinceMs = clock.now()
                log.error(
                    "portfolio book unreadable: {} — refusing new entries on all children until it reads again; " +
                        "open positions and their exits stay",
                    it,
                )
            }
        for (child in children) {
            if (!child.isHalted()) {
                runCatching { child.halt(active, HaltScope.TRANSIENT) }
                    .onFailure { log.warn("child hold failed: {}", it.message) }
            }
            if (child.isHalted() && child.haltReason() == active) held += child
        }
    }

    /** The book reads again, or an operator resumed it: resumes each child still under this hold. */
    fun release() {
        val active = reason ?: return
        for (child in held) {
            if (child.isHalted() && child.haltReason() == active && child.haltScope() == HaltScope.TRANSIENT) {
                runCatching { child.resume() }.onFailure { log.warn("child resume failed: {}", it.message) }
            }
        }
        log.warn("portfolio book hold released; children take new entries again: {}", active)
        handOver()
    }

    /** A book halt takes the children over; their hold escalates to it, so there is nothing left to resume. */
    fun handOver() {
        held.clear()
        reason = null
        sinceMs = 0L
    }
}
