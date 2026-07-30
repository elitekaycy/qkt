package com.qkt.cli.daemon.portfolio

import com.qkt.common.Clock
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/** A child the book can act on when the account-level limit trips. */
interface ChildRiskTarget {
    /** Closes the child's open exposure. */
    fun flatten()

    /** Applies a halt with the book rule's [scope]. */
    fun halt(
        reason: String,
        scope: HaltScope,
    )

    /** Clears the child's active global halt. */
    fun resume()

    /** True when any global halt currently suppresses the child. */
    fun isHalted(): Boolean

    /** Active global halt reason, when available. */
    fun haltReason(): String?

    /** Active global halt scope, when available. */
    fun haltScope(): HaltScope?
}

/**
 * Account-level drawdown halt for a portfolio book. Refreshes a book [RiskState] (fed by summed
 * child PnL) and runs the existing drawdown halt rules; on a breach it flattens then halts every
 * child. A DAILY breach (daily-loss / daily-drawdown) auto-resumes only children whose halt still
 * matches that book breach at the next UTC midnight; a PERSISTENT breach (total / trailing
 * drawdown), or an unrelated child halt, stays latched across process restarts.
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
                    bookRiskState.haltReason ?: "restored portfolio book halt",
                    bookRiskState.globalHaltScope() ?: HaltScope.PERSISTENT,
                )
            }
            return
        }
        if (childrenHalted) {
            resumeBookHaltedChildren()
        }
        bookRiskState.onTick()
        val breach =
            haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt } ?: return
        bookRiskState.halt(breach.reason, breach.scope)
        haltChildren(breach.reason, breach.scope)
    }

    /** Feed one candle-aligned book snapshot into the controller. */
    @Synchronized
    fun sample(timestampMs: Long) {
        onSample(timestampMs)
    }

    /** Fail closed when the supervisor cannot evaluate book risk repeatedly. */
    @Synchronized
    fun failClosed(reason: String) {
        if (!bookRiskState.halted) bookRiskState.halt(reason, HaltScope.PERSISTENT)
        if (!childrenHalted) haltChildren(reason, HaltScope.PERSISTENT)
    }

    private fun haltChildren(
        reason: String,
        scope: HaltScope,
    ) {
        childrenHalted = true
        childHaltReason = reason
        childHaltScope = scope
        log.warn("portfolio book drawdown breached: {} — flattening and halting all children", reason)
        for (c in children) {
            runCatching { c.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
            if (!c.isHalted()) {
                runCatching { c.halt(reason, scope) }.onFailure { log.warn("child halt failed: {}", it.message) }
            }
            if (c.isHalted() && c.haltReason() == reason && c.haltScope() == scope) {
                bookHaltedChildren.add(c)
            }
        }
    }

    private fun resumeBookHaltedChildren() {
        childrenHalted = false
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
}

/** Buffers child fills until the book risk state is constructed, then forwards directly. */
internal class PortfolioRiskFillBuffer {
    private data class Fill(
        val strategyId: String,
        val realized: BigDecimal,
    )

    private val pending = ConcurrentLinkedQueue<Fill>()
    private val target = AtomicReference<PortfolioRiskAggregator?>()

    fun record(
        strategyId: String,
        realized: BigDecimal,
    ) {
        val bound = target.get()
        if (bound != null) {
            bound.recordRealized(strategyId, realized)
            return
        }
        pending.add(Fill(strategyId, realized))
        target.get()?.let(::drain)
    }

    fun bind(aggregator: PortfolioRiskAggregator) {
        check(target.compareAndSet(null, aggregator)) { "portfolio risk fill buffer already bound" }
        drain(aggregator)
    }

    private fun drain(aggregator: PortfolioRiskAggregator) {
        while (true) {
            val fill = pending.poll() ?: return
            aggregator.recordRealized(fill.strategyId, fill.realized)
        }
    }
}
