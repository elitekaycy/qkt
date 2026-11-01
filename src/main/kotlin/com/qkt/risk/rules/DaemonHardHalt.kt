package com.qkt.risk.rules

import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState

/**
 * Keeps a session halted while the daemon's `risk.hard_daily_loss` stands: after a resume, and for a session
 * deployed since it tripped. Daily scope, so the session resumes at the next UTC midnight. The flatten is the
 * one thing this does not do; [com.qkt.cli.daemon.DaemonHardLimitGuard] does that, once.
 */
class DaemonHardHalt(
    private val scope: DaemonRiskScope,
) : HaltRule {
    override fun evaluate(riskState: RiskState): HaltDecision =
        scope.hardHaltReason()?.let { HaltDecision.Halt(it, scope = HaltScope.DAILY) } ?: HaltDecision.Continue
}
