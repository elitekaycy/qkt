package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import org.slf4j.Logger

/**
 * Compiles `OCO_ENTRY { <leg1>; <leg2> }`: two entry orders, each compiled as its own action,
 * submitted as one [OrderRequest.StandaloneOCO] so the first to fill cancels the other.
 */
internal class OcoEntryCompiler(
    private val ids: IdGenerator,
    private val strategyLogger: Logger,
) {
    fun compile(
        action: OcoEntry,
        actions: ActionCompiler,
    ): (EvalContext) -> List<Signal> {
        for (leg in listOf(action.leg1, action.leg2)) {
            val legTimes =
                when (leg) {
                    is Buy -> leg.opts.times
                    is Sell -> leg.opts.times
                    else -> null
                }
            require(legTimes == null) { "OCO_ENTRY legs cannot carry TIMES; repeat the OCO_ENTRY action instead" }
        }
        val leg1Compiled = actions.compile(action.leg1)
        val leg2Compiled = actions.compile(action.leg2)
        var skippedUndefinedLogged = false
        return ocoEntry@{ ctx ->
            val sigs1 = leg1Compiled(ctx)
            val sigs2 = leg2Compiled(ctx)
            if (sigs1.isEmpty() || sigs2.isEmpty()) {
                if (!skippedUndefinedLogged) {
                    strategyLogger.warn(
                        "order skipped: OCO_ENTRY leg price undefined during warm-up " +
                            "(strategy=${ctx.strategyContext.strategyId})",
                    )
                    skippedUndefinedLogged = true
                }
                return@ocoEntry emptyList()
            }
            val req1 =
                (sigs1.singleOrNull() as? Signal.Submit)?.request
                    ?: error("OCO_ENTRY leg1 must compile to exactly one Signal.Submit, got $sigs1")
            val req2 =
                (sigs2.singleOrNull() as? Signal.Submit)?.request
                    ?: error("OCO_ENTRY leg2 must compile to exactly one Signal.Submit, got $sigs2")
            val oco =
                OrderRequest.StandaloneOCO(
                    id = ids.next(),
                    symbol = req1.symbol,
                    side = req1.side,
                    quantity = req1.quantity,
                    leg1 = req1,
                    leg2 = req2,
                    timeInForce = req1.timeInForce,
                    timestamp = ctx.strategyContext.clock.now(),
                )
            listOf(Signal.Submit(oco))
        }
    }
}
