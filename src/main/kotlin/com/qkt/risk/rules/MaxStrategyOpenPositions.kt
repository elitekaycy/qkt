package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

/**
 * Rejects new-symbol entries that would push the strategy's concurrent position count past
 * [maxCount]. Add-to-existing-position orders are always approved — only "opening on a new
 * symbol" counts toward the cap.
 *
 * A symbol counts as held when the strategy has a filled position on it **or** a live entry order
 * for it. Counting only filled positions let a burst walk straight through the limit: live, every
 * order of a burst is risk-checked before any of its fills return, so each one saw an empty book
 * and was approved, and a strategy capped at one symbol opened two. A backtest, where fills land
 * between submissions, enforced the cap and rejected the second symbol — the same strategy,
 * the same config, two different answers. Reserving the in-flight symbol makes the cap bind the
 * same way regardless of when fills arrive.
 */
class MaxStrategyOpenPositions(
    private val strategyId: String,
    private val maxCount: Int,
    private val strategyPositions: StrategyPositionTracker,
) : RiskRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxCount > 0) { "maxCount must be > 0: $maxCount" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (request.strategyId != strategyId) return Decision.Approve
        val held = strategyPositions.positionsFor(strategyId).keys
        val inFlight = positions.pendingEntrySymbols(strategyId)
        if (request.symbol in held || request.symbol in inFlight) return Decision.Approve
        val current = (held + inFlight).size
        return if (current < maxCount) {
            Decision.Approve
        } else {
            Decision.Reject(
                "MaxStrategyOpenPositions[$strategyId]: $current already open, max $maxCount",
            )
        }
    }
}
