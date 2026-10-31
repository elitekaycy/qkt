package com.qkt.risk.rules

import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState

/**
 * Makes the global rule [rule] of [session] bind the whole daemon: its breach halts the other sessions,
 * and a breach of theirs halts this one. It carries rule breaches only: an engine fault or an operator
 * halt stays on its session. A session an operator resumes while the breach stands halts again, as a
 * session whose own rule still fires does; one resumed after the breach ended stays resumed.
 */
class DaemonWideHalt(
    private val session: String,
    private val rule: HaltRule,
    private val scope: DaemonRiskScope,
) : HaltRule {
    override fun evaluate(riskState: RiskState): HaltDecision {
        val decision = rule.evaluate(riskState)
        scope.decided(session, this, decision)
        if (decision is HaltDecision.Halt) return decision
        return scope.breachElsewhere(session) ?: decision
    }
}
