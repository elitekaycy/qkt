package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import org.slf4j.LoggerFactory

class RiskEngine(
    private val rules: List<RiskRule>,
    private val haltRules: List<HaltRule>,
    private val positions: PositionProvider,
    private val riskState: RiskState,
) {
    private val log = LoggerFactory.getLogger(RiskEngine::class.java)

    constructor(
        rules: List<RiskRule>,
        positions: PositionProvider,
    ) : this(rules, emptyList(), positions, RiskState.noOp())

    fun approve(request: OrderRequest): Decision {
        if (riskState.isStrategyHalted(request.strategyId)) {
            val reason = riskState.haltReasonFor(request.strategyId) ?: "halted"
            return Decision.Reject("halted: $reason")
        }
        for (rule in rules) {
            val decision = rule.evaluate(request, positions)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }

    fun evaluateHaltRules() {
        if (!riskState.warmupComplete) return
        for (rule in haltRules) {
            runCatching {
                when (val decision = rule.evaluate(riskState)) {
                    HaltDecision.Continue -> Unit
                    is HaltDecision.Halt ->
                        if (decision.strategyId != null) {
                            riskState.haltStrategy(decision.strategyId, decision.reason)
                        } else {
                            riskState.halt(decision.reason)
                        }
                }
            }.onFailure { log.warn("HaltRule {} threw: {}", rule::class.simpleName, it.message) }
        }
    }
}
