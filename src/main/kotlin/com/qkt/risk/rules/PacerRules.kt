package com.qkt.risk.rules

import com.qkt.common.Clock
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.PacerLedger
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.risk.isRiskReducing

/** Rejects risk-increasing orders after [maxTrades] entry fills in the current UTC day. */
class MaxTradesPerDay(
    private val maxTrades: Int,
    private val ledger: PacerLedger,
    private val clock: Clock,
    private val strategyId: String? = null,
) : RiskRule {
    init {
        require(maxTrades > 0) { "maxTrades must be > 0: $maxTrades" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (strategyId != null && request.strategyId != strategyId) return Decision.Approve
        if (isRiskReducing(request, positions)) return Decision.Approve
        val count = ledger.tradesToday(request.strategyId, nowMs(request, clock))
        return if (count < maxTrades) {
            Decision.Approve
        } else {
            Decision.Reject("MaxTradesPerDay[${request.strategyId}]: $count entries today, max $maxTrades")
        }
    }
}

/** Rejects risk-increasing orders until [durationMs] has elapsed after enough consecutive losses. */
class CooldownAfterLoss(
    private val durationMs: Long,
    private val ledger: PacerLedger,
    private val clock: Clock,
    private val afterConsecutive: Int = 1,
    private val strategyId: String? = null,
) : RiskRule {
    init {
        require(durationMs > 0) { "durationMs must be > 0: $durationMs" }
        require(afterConsecutive > 0) { "afterConsecutive must be > 0: $afterConsecutive" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (strategyId != null && request.strategyId != strategyId) return Decision.Approve
        if (isRiskReducing(request, positions)) return Decision.Approve
        val remaining =
            ledger.cooldownRemainingMs(
                request.strategyId,
                nowMs(request, clock),
                durationMs,
                afterConsecutive,
            )
        return if (remaining <= 0L) {
            Decision.Approve
        } else {
            Decision.Reject("CooldownAfterLoss[${request.strategyId}]: ${remaining / 1_000L}s remaining")
        }
    }
}

private fun nowMs(
    request: OrderRequest,
    clock: Clock,
): Long = request.timestamp.takeIf { it > 0L } ?: clock.now()

/** Halts [strategyId] when its consecutive loss streak reaches [maxLosses]. */
class LossStreakHalt(
    private val strategyId: String,
    private val maxLosses: Int,
    private val ledger: PacerLedger,
    private val scope: HaltScope = HaltScope.PERSISTENT,
) : HaltRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxLosses > 0) { "maxLosses must be > 0: $maxLosses" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val losses = ledger.lossStreak(strategyId)
        return if (losses >= maxLosses) {
            HaltDecision.Halt(
                reason = "LossStreakHalt[$strategyId]: $losses consecutive losses, max $maxLosses",
                strategyId = strategyId,
                scope = scope,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
