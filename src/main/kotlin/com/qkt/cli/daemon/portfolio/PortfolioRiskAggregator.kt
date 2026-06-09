package com.qkt.cli.daemon.portfolio

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import org.slf4j.LoggerFactory

/** A child the book can act on when the account-level limit trips. */
interface ChildRiskTarget {
    fun flatten()

    fun halt(reason: String)
}

/**
 * Account-level drawdown halt for a portfolio book. Refreshes a book [RiskState] (fed by summed
 * child PnL) and runs the existing drawdown halt rules; on the first breach it flattens then halts
 * every child. Latched per deployment — fires once; a redeploy builds a fresh aggregator. An
 * operator `resume` on a child is a manual override and does not un-trip or re-trip the book.
 */
class PortfolioRiskAggregator(
    private val children: List<ChildRiskTarget>,
    private val bookRiskState: RiskState,
    private val haltRules: List<HaltRule>,
) {
    private val log = LoggerFactory.getLogger(PortfolioRiskAggregator::class.java)

    @Volatile
    private var tripped = false

    fun evaluate() {
        if (tripped) return
        bookRiskState.onTick()
        val breach =
            haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt } ?: return
        tripped = true
        log.warn("portfolio book drawdown breached: {} — flattening and halting all children", breach.reason)
        for (c in children) {
            runCatching { c.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
            runCatching { c.halt(breach.reason) }.onFailure { log.warn("child halt failed: {}", it.message) }
        }
    }
}
