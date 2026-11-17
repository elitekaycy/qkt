package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

/**
 * [MaxStrategyOpenPositions] over one symbol set: rejects an entry on a member of the set the strategy does not
 * already hold or have entering when the set's held plus in-flight members already number [maxCount]. Orders on
 * symbols outside the set, and adds to a member already held, are approved; so are other strategies' orders. The
 * members are read from [members] at each check, so a set whose membership moves during a run counts whoever is a
 * member then.
 */
class MaxSetOpenPositions(
    private val strategyId: String,
    private val set: String,
    private val members: () -> Set<String>,
    private val maxCount: Int,
    private val strategyPositions: StrategyPositionTracker,
) : RiskRule {
    /** A set whose members are fixed for the run; it must have at least one. */
    constructor(
        strategyId: String,
        set: String,
        members: Set<String>,
        maxCount: Int,
        strategyPositions: StrategyPositionTracker,
    ) : this(strategyId, set, { members }, maxCount, strategyPositions) {
        require(members.isNotEmpty()) { "set '$set' has no members" }
    }

    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxCount > 0) { "maxCount must be > 0: $maxCount" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val members = members()
        if (request.strategyId != strategyId || request.symbol !in members) return Decision.Approve
        val held = strategyPositions.positionsFor(strategyId).keys.filter { it in members }
        val inFlight = positions.pendingEntrySymbols(strategyId).filter { it in members }
        if (request.symbol in held || request.symbol in inFlight) return Decision.Approve
        val current = (held + inFlight).toSet().size
        return if (current < maxCount) {
            Decision.Approve
        } else {
            Decision.Reject("MaxSetOpenPositions[$strategyId/$set]: $current already open, max $maxCount")
        }
    }
}
