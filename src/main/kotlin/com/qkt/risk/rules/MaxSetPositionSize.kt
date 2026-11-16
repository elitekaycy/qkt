package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * [MaxStrategyPositionSize] over one symbol set: rejects an order that would push the strategy's net position on a
 * member of the set, filled plus pending, past [maxQty]. Symbols outside the set are untouched. The members are read
 * from [members] at each check, so a set whose membership moves during a run caps whoever is a member then.
 */
class MaxSetPositionSize(
    private val strategyId: String,
    private val set: String,
    private val members: () -> Set<String>,
    private val maxQty: BigDecimal,
    private val strategyPositions: StrategyPositionTracker,
) : RiskRule {
    /** A set whose members are fixed for the run; it must have at least one. */
    constructor(
        strategyId: String,
        set: String,
        members: Set<String>,
        maxQty: BigDecimal,
        strategyPositions: StrategyPositionTracker,
    ) : this(strategyId, set, { members }, maxQty, strategyPositions) {
        require(members.isNotEmpty()) { "set '$set' has no members" }
    }

    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (request.strategyId != strategyId || request.symbol !in members()) return Decision.Approve
        val current = strategyPositions.positionFor(strategyId, request.symbol)?.quantity ?: Money.ZERO
        val pending = positions.pendingOrderQuantity(request.symbol, request.side, strategyId)
        val projected =
            if (request.side == Side.BUY) {
                current.add(pending).add(request.quantity)
            } else {
                current.subtract(pending).subtract(request.quantity)
            }
        return if (projected.abs().compareTo(maxQty) <= 0) {
            Decision.Approve
        } else {
            Decision.Reject(
                "MaxSetPositionSize[$strategyId/$set]: |$projected| > $maxQty for ${request.symbol} (pending=$pending)",
            )
        }
    }
}
