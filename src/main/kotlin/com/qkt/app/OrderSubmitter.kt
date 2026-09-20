package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.scaleQuantity
import com.qkt.execution.toOrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.isRiskReducing
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Turns an order-bearing strategy signal into venue orders: builds the request, links it to the
 * rule decision that produced it, applies the book scale, asks risk, and publishes each routed
 * leg as an [OrderEvent]. One instance serves every strategy in the pipeline.
 */
internal class OrderSubmitter(
    private val ids: IdGenerator,
    private val clock: Clock,
    private val bus: EventBus,
    private val riskEngine: RiskEngine,
    private val positions: PositionProvider,
    private val strategyPositions: StrategyPositionTracker,
    private val priceTracker: MarketPriceTracker,
    private val exitHookManager: ExitHookManager,
    private val positionMode: (symbol: String) -> PositionAccountingMode,
    private val bookScaleFor: (String) -> BigDecimal,
) {
    // Logged under the pipeline's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    /** Submit [sig] for [strategyId]; the outcome is recorded on [ctx]'s submission counters. */
    fun submit(
        strategyId: String,
        strategy: Strategy,
        ctx: StrategyContext,
        sig: Signal,
    ) {
        val built = sig.toOrderRequest(ids.next(), clock.now(), strategyId = strategyId) ?: return
        val decisionLink = (strategy as? DslCompiledStrategy)?.onOrderSubmitted(sig, built.id)
        decisionLink?.let { link ->
            bus.publish(
                DecisionOrderLinkedEvent(
                    strategyId = strategyId,
                    decisionId = link.decisionId,
                    ruleId = link.ruleId,
                    signalIndex = link.signalIndex,
                    orderId = link.orderId,
                ),
            )
        }
        val request = applyBookScale(built)
        if (request == null) {
            ctx.submissions.recordSuppressed()
            bus.publish(RiskRejectedEvent(built, "book de-risk: new risk suppressed"))
            return
        }
        logSubmitContext(request)
        when (val decision = riskEngine.approve(request)) {
            is Decision.Approve -> {
                ctx.submissions.recordAccepted()
                val exitHook =
                    when (sig) {
                        is Signal.Buy -> sig.exitHook
                        is Signal.Sell -> sig.exitHook
                        is Signal.Submit -> sig.exitHook
                        else -> null
                    }
                val mode = positionMode(request.symbol)
                val openLegs = strategyPositions.legBookFor(strategyId, request.symbol)?.all().orEmpty()
                val routed = HedgedEntryRouter.route(request, mode, openLegs, ids::next)
                for (leaf in routed) {
                    val planned = LegIntentPlanner.plan(leaf, mode)
                    val isClose = planned is OrderRequest.Market && planned.closesTicket != null
                    if (exitHook != null && !isClose) {
                        exitHookManager.register(strategyId, planned, exitHook)
                    }
                    exitHookManager.trackCloseRequest(strategyId, planned)
                    bus.publish(OrderEvent(planned))
                }
            }
            is Decision.Reject -> {
                ctx.submissions.recordSuppressed()
                bus.publish(RiskRejectedEvent(request, decision.reason))
            }
        }
    }

    /**
     * Apply the book scale to a new order: a scale of exactly 1.0 and risk-reducing orders pass
     * unchanged; a scale of 0 suppresses the order (returns null); any other scale multiplies every
     * quantity (down to de-risk, up to a vol/allocation target).
     */
    private fun applyBookScale(req: OrderRequest): OrderRequest? {
        val f = bookScaleFor(req.strategyId)
        if (f.compareTo(BigDecimal.ONE) == 0) return req
        if (isRiskReducing(req, positions)) return req
        if (f.signum() <= 0) return null
        return req.scaleQuantity(f)
    }

    /**
     * INFO-log the order's price-bearing fields and the last price seen for the symbol at submit
     * time, so a later `Order rejected` WARN (which carries only the `clientOrderId`) has context.
     * e.g. `submit Stop dsl-hedge_straddle--1 EXNESS:XAUUSDm BUY stopPrice=2350.50 lastPrice=2350.20`
     * shows a BUY_STOP ~30c above the last-seen price that the gateway's drifted ask passed (#185).
     */
    private fun logSubmitContext(request: OrderRequest) {
        if (!log.isInfoEnabled) return
        val lastPrice = priceTracker.lastPrice(request.symbol)
        val kind = request::class.simpleName
        val priceFields = submitPriceFields(request)
        log.info(
            "submit {} {} {} {} {} qty={} {} lastPrice={}",
            kind,
            request.id,
            request.symbol,
            request.side,
            request.timeInForce,
            request.quantity,
            priceFields,
            lastPrice ?: "unknown",
        )
    }
}
