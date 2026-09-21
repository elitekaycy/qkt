package com.qkt.app

import com.qkt.observe.insights.InsightsEnvelope
import com.qkt.risk.RiskState

/**
 * One strategy's halt state as the session sees it right now, for the dashboard: halted or not,
 * why, how long it lasts and when it tripped, e.g. `{halted: true, haltReason: "global drawdown
 * 0.1004 exceeds max 0.1", haltScope: "PERSISTENT", haltPersistent: true}`.
 *
 * Sent when a session starts. A halt restored from disk is otherwise never announced again, and a
 * dashboard replaying halt and resume events keeps whatever the last one said - an older engine's
 * session-wide halt then marks every strategy of the daemon halted for good.
 */
internal fun riskSnapshotOf(
    riskState: RiskState,
    strategyId: String,
    ts: Long,
): InsightsEnvelope {
    val own = riskState.strategyHalts().firstOrNull { it.strategyId == strategyId }
    val halted = riskState.halted || own != null
    val scope = if (riskState.halted) riskState.globalHaltScope()?.name else own?.scope
    val haltedAt = if (riskState.halted) riskState.globalHaltedAtMs() else own?.haltedAtMs?.takeIf { it > 0L }
    return InsightsEnvelope(
        id = "risk-snapshot-$strategyId-$ts",
        seq = 0,
        ts = ts,
        strategyId = strategyId,
        type = "risk.snapshot",
        payload =
            linkedMapOf(
                "strategyId" to strategyId,
                "ts" to ts,
                "halted" to halted,
                "haltReason" to if (halted) riskState.haltReasonFor(strategyId) else null,
                "haltScope" to if (halted) scope else null,
                "haltPersistent" to if (halted) scope == "PERSISTENT" else null,
                "haltedAt" to if (halted) haltedAt else null,
            ),
    )
}
