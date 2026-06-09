package com.qkt.cli.daemon.portfolio

import com.qkt.common.Clock
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.time.Instant
import java.time.ZoneOffset
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
 * PERSISTENT breach (total / trailing drawdown) stays latched until a redeploy.
 */
class PortfolioRiskAggregator(
    private val children: List<ChildRiskTarget>,
    private val bookRiskState: RiskState,
    private val haltRules: List<HaltRule>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PortfolioRiskAggregator::class.java)

    @Volatile
    private var tripped = false

    @Volatile
    private var trippedScope: HaltScope = HaltScope.PERSISTENT

    @Volatile
    private var trippedDay: Long = 0L

    fun evaluate() {
        if (tripped) {
            if (trippedScope == HaltScope.DAILY && epochDay() > trippedDay) {
                tripped = false
                children.forEach { c -> runCatching { c.resume() } }
            } else {
                return
            }
        }
        bookRiskState.onTick()
        val breach =
            haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt } ?: return
        tripped = true
        trippedScope = breach.scope
        trippedDay = epochDay()
        log.warn("portfolio book drawdown breached: {} — flattening and halting all children", breach.reason)
        for (c in children) {
            runCatching { c.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
            runCatching { c.halt(breach.reason) }.onFailure { log.warn("child halt failed: {}", it.message) }
        }
    }

    private fun epochDay(): Long =
        Instant
            .ofEpochMilli(clock.now())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()
}
