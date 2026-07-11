package com.qkt.cli.daemon.portfolio

import com.qkt.common.Clock
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/** A child the book can act on when the account-level limit trips. */
interface ChildRiskTarget {
    fun flatten()

    fun halt(reason: String)

    fun resume()
}

/**
 * Account-level drawdown halt for a portfolio book. Refreshes a book [RiskState] (fed by summed
 * child PnL) and runs the existing drawdown halt rules; on a breach it flattens then halts every
 * child. A DAILY breach (daily-loss / daily-drawdown) auto-resumes at the next UTC midnight; a
 * PERSISTENT breach (total / trailing drawdown) stays latched across process restarts.
 */
class PortfolioRiskAggregator(
    private val children: List<ChildRiskTarget>,
    private val bookRiskState: RiskState,
    private val haltRules: List<HaltRule>,
    private val clock: Clock,
    private val onSample: (Long) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(PortfolioRiskAggregator::class.java)
    private var childrenHalted = false

    /** Feed one child fill into the book's single daily-PnL and equity state writer. */
    @Synchronized
    fun recordRealized(
        strategyId: String,
        realized: BigDecimal,
    ) {
        bookRiskState.onFill(strategyId, realized)
    }

    @Synchronized
    fun evaluate() {
        onSample(clock.now())
        bookRiskState.clearExpiredDailyHalts()
        if (bookRiskState.halted) {
            if (!childrenHalted) {
                haltChildren(bookRiskState.haltReason ?: "restored portfolio book halt")
            }
            return
        }
        if (childrenHalted) {
            childrenHalted = false
            children.forEach { child -> runCatching { child.resume() } }
        }
        bookRiskState.onTick()
        val breach =
            haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt } ?: return
        bookRiskState.halt(breach.reason, breach.scope)
        haltChildren(breach.reason)
    }

    private fun haltChildren(reason: String) {
        childrenHalted = true
        log.warn("portfolio book drawdown breached: {} — flattening and halting all children", reason)
        for (c in children) {
            runCatching { c.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
            runCatching { c.halt(reason) }.onFailure { log.warn("child halt failed: {}", it.message) }
        }
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
