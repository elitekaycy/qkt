package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import org.slf4j.LoggerFactory

/**
 * Pre-trade risk gate. Every [OrderRequest] flowing from the strategy layer to the
 * broker goes through [approve]; rejected requests never reach the venue.
 *
 * Two rule lists run independently:
 * - **Per-request rules** ([RiskRule]) — evaluated on every order, short-circuit on
 *   the first rejection. Used for caps (max position size, max open positions, etc).
 * - **Halt rules** ([HaltRule]) — evaluated against running state by [RiskState]; can
 *   put a strategy into a halted state where every subsequent submission auto-rejects
 *   without consulting the per-request rules.
 *
 * The engine is single-threaded by design — call [approve] from the same thread that
 * publishes order events onto the bus so the halt-vs-approve decision stays consistent
 * with the order book state.
 */
class RiskEngine(
    private val rules: List<RiskRule>,
    private val haltRules: List<HaltRule>,
    private val positions: PositionProvider,
    private val riskState: RiskState,
) {
    private val log = LoggerFactory.getLogger(RiskEngine::class.java)

    /** Convenience constructor for tests / single-strategy setups with no halt rules. */
    constructor(
        rules: List<RiskRule>,
        positions: PositionProvider,
    ) : this(rules, emptyList(), positions, RiskState.noOp())

    /** Run the rules over [request] and return whether the venue should see it. */
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
