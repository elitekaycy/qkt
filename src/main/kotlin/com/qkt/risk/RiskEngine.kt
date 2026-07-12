package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PendingOrderExposureProvider
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
    private var pendingExposure: PendingOrderExposureProvider = PendingOrderExposureProvider.NONE
    private val positionsWithPendingExposure =
        object : PositionProvider {
            override fun positionFor(symbol: String) = positions.positionFor(symbol)

            override fun allPositions() = positions.allPositions()

            override fun symbols() = positions.symbols()

            override fun pendingOrderQuantity(
                symbol: String,
                side: com.qkt.common.Side,
                strategyId: String?,
            ) = pendingExposure.quantityFor(symbol, side, strategyId)
        }

    /** Convenience constructor for tests / single-strategy setups with no halt rules. */
    constructor(
        rules: List<RiskRule>,
        positions: PositionProvider,
    ) : this(rules, emptyList(), positions, RiskState.noOp())

    internal fun bindPendingExposure(provider: PendingOrderExposureProvider) {
        pendingExposure = provider
    }

    /** Run the rules over [request] and return whether the venue should see it. */
    fun approve(request: OrderRequest): Decision {
        if (riskState.isStrategyHalted(request.strategyId)) {
            // A halt blocks NEW exposure, not the way out: risk-REDUCING orders (closes
            // by ticket, opposite-side orders no larger than the open position) must
            // still pass, or a halted strategy cannot exit the situation the halt
            // declared bad (FIA §7.3). They still run the per-request caps below.
            if (!isRiskReducing(request, positions)) {
                val reason = riskState.haltReasonFor(request.strategyId) ?: "halted"
                return Decision.Reject("halted: $reason")
            }
            log.info("halted but allowing risk-reducing order {} for {}", request.id, request.symbol)
        }
        for (rule in rules) {
            val decision = rule.evaluate(request, positionsWithPendingExposure)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }

    fun evaluateHaltRules() {
        if (!riskState.warmupComplete) return
        riskState.clearExpiredDailyHalts()
        for (rule in haltRules) {
            // Plain try/catch: this runs per rule on every tick, and runCatching's two capturing
            // lambdas allocated on each pass.
            try {
                when (val decision = rule.evaluate(riskState)) {
                    HaltDecision.Continue -> Unit
                    is HaltDecision.Halt ->
                        if (decision.strategyId != null) {
                            riskState.haltStrategy(decision.strategyId, decision.reason, decision.scope)
                        } else {
                            riskState.halt(decision.reason, decision.scope)
                        }
                }
            } catch (e: Exception) {
                log.warn("HaltRule {} threw: {}", rule::class.simpleName, e.message)
            }
        }
    }
}
