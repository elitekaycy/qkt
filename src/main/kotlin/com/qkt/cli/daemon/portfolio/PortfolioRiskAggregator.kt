package com.qkt.cli.daemon.portfolio

import com.qkt.common.Clock
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory

/**
 * Account-level drawdown halt for a portfolio book. Refreshes a book [RiskState] (fed by summed
 * child PnL) and runs the existing drawdown halt rules; on a breach it flattens then halts every
 * child. That close covers what each child holds when its engine reaches it; an entry already in
 * flight that fills after it is closed by [LateFillCloser]. A DAILY breach (daily-loss / daily-drawdown) auto-resumes only children whose halt still
 * matches that book breach at the next UTC midnight; a PERSISTENT breach (total / trailing
 * drawdown), or an unrelated child halt, stays latched across process restarts, until an operator clears it with
 * [resumeByOperator] (`qkt resume <book>`). A book that cannot be evaluated is held off new entries by
 * [UnreadableBookHold], not halted.
 */
class PortfolioRiskAggregator(
    private val children: List<ChildRiskTarget>,
    private val bookRiskState: RiskState,
    private val haltRules: List<HaltRule>,
    private val clock: Clock,
    private val onSample: (Long) -> Unit = {},
    private val sampleOnEvaluate: Boolean = true,
) {
    private data class RealizedFill(
        val strategyId: String,
        val realized: BigDecimal,
    )

    private val log = LoggerFactory.getLogger(PortfolioRiskAggregator::class.java)
    private val pendingFills = ConcurrentLinkedQueue<RealizedFill>()
    private var childrenHalted = false
    private val bookHaltedChildren = mutableSetOf<ChildRiskTarget>()
    private var childHaltReason: String? = null
    private var childHaltScope: HaltScope? = null
    private val lateFills = LateFillCloser(clock)
    private val unreadable = UnreadableBookHold(clock)

    /** Queue one child fill for the book's single risk-heartbeat writer. */
    fun recordRealized(
        strategyId: String,
        realized: BigDecimal,
    ) {
        pendingFills.add(RealizedFill(strategyId, realized))
    }

    @Synchronized
    fun evaluate() {
        // Child snapshots can wait for their engine threads. Fill callbacks only enqueue above,
        // so an engine never waits for this monitor while the risk heartbeat waits for it.
        while (true) {
            val fill = pendingFills.peek() ?: break
            bookRiskState.onFill(fill.strategyId, fill.realized)
            pendingFills.poll()
        }
        if (sampleOnEvaluate) sample(clock.now())
        bookRiskState.clearExpiredDailyHalts()
        if (bookRiskState.halted) {
            if (!childrenHalted) {
                haltChildren(
                    bookRiskState.haltReason ?: RESTORED_REASON,
                    bookRiskState.globalHaltScope() ?: HaltScope.PERSISTENT,
                )
            } else {
                lateFills.sweep(children)
            }
            return
        }
        if (childrenHalted) {
            resumeBookHaltedChildren()
        }
        bookRiskState.onTick()
        // The book read: a breach takes the held children over, otherwise they take entries again.
        val breach = haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt }
        if (breach == null) {
            unreadable.release()
            return
        }
        bookRiskState.halt(breach.reason, breach.scope)
        haltChildren(breach.reason, breach.scope)
    }

    private val boundary = com.qkt.backtest.OncePerBoundary()

    /**
     * Feed one candle-aligned book snapshot into the controller: once per boundary, however many of the book's
     * symbols close a bar at [timestampMs], as the backtest book samplers do.
     */
    @Synchronized
    fun sample(timestampMs: Long) {
        if (boundary.first(timestampMs)) onSample(timestampMs)
    }

    /**
     * Fail closed when the supervisor cannot evaluate book risk repeatedly: hold every child off new entries until the
     * book reads again (see [UnreadableBookHold]). A book already halted holds its children already.
     */
    @Synchronized
    fun failClosed(reason: String) {
        if (bookRiskState.halted || childrenHalted) return
        unreadable.hold(reason, children)
    }

    /** The book's active halt, or its unreadable hold; null when the book trades. Never waits on an evaluation. */
    fun bookHalt(): BookHalt? {
        if (!bookRiskState.halted) return unreadable.view()
        return BookHalt(
            bookRiskState.haltReason ?: RESTORED_REASON,
            bookRiskState.globalHaltScope() ?: HaltScope.PERSISTENT,
            bookRiskState.globalHaltedAtMs(),
        )
    }

    /**
     * Operator resume (`qkt resume <book>`): clears the book's halt, or its unreadable hold, and the halts it put on
     * its children; a child halted for its own reason stays halted. Returns what was cleared, null when the book was
     * not halted. A limit still breached halts the book again on the next evaluation.
     */
    @Synchronized
    fun resumeByOperator(): BookHalt? {
        val cleared = bookHalt() ?: return null
        val reasons = setOfNotNull(cleared.reason, childHaltReason)
        unreadable.release()
        bookRiskState.resume()
        for (child in children) {
            if (child.isHalted() && child.haltReason() in reasons) {
                runCatching { child.resume() }.onFailure { log.warn("child resume failed: {}", it.message) }
            }
        }
        childrenHalted = false
        bookHaltedChildren.clear()
        childHaltReason = null
        childHaltScope = null
        lateFills.clear()
        log.warn("portfolio book halt cleared by operator: {}", cleared.reason)
        return cleared
    }

    private fun haltChildren(
        reason: String,
        scope: HaltScope,
    ) {
        childrenHalted = true
        unreadable.handOver()
        childHaltReason = reason
        childHaltScope = scope
        log.warn(
            "portfolio book halted: {} — closing open positions and refusing new entries on all children; " +
                "an entry already in flight that fills after this is closed too",
            reason,
        )
        for (c in children) {
            runCatching { c.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
            lateFills.closed(c)
            // A child held only until midnight by the daemon's total must carry a persistent book breach past it.
            // A child only held off entries while the book was unreadable takes the breach's halt.
            val escalates =
                (scope == HaltScope.PERSISTENT && c.haltScope() == HaltScope.DAILY) ||
                    (scope != HaltScope.TRANSIENT && c.haltScope() == HaltScope.TRANSIENT)
            if (!c.isHalted() || escalates) {
                runCatching { c.halt(reason, scope) }.onFailure { log.warn("child halt failed: {}", it.message) }
            }
            if (c.isHalted() && c.haltReason() == reason && c.haltScope() == scope) {
                bookHaltedChildren.add(c)
            }
        }
    }

    private fun resumeBookHaltedChildren() {
        childrenHalted = false
        lateFills.clear()
        val reason = childHaltReason
        val scope = childHaltScope
        for (child in bookHaltedChildren) {
            if (child.isHalted() && child.haltReason() == reason && child.haltScope() == scope) {
                runCatching { child.resume() }.onFailure { log.warn("child resume failed: {}", it.message) }
            }
        }
        bookHaltedChildren.clear()
        childHaltReason = null
        childHaltScope = null
    }

    private companion object {
        const val RESTORED_REASON = "restored portfolio book halt"
    }
}
