package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/**
 * Builds the exit `OCO` of a `BUY`/`SELL ... OCO STOP ... LIMIT ...` action: a stop leg and a
 * limit leg on the exit side, wrapped in one [OrderRequest.StandaloneOCO]. Returns null (after a
 * once-only warm-up log) when either leg's price is still undefined.
 */
internal class OcoExitOrderBuilder(
    private val ids: IdGenerator,
) {
    fun build(
        compiledOcoLeg1: CompiledChildPrice?,
        compiledOcoLeg2: CompiledChildPrice?,
        ctx: EvalContext,
        side: Side,
        entry: BigDecimal,
        symbol: String,
        qty: BigDecimal,
        tif: TimeInForce,
        ts: Long,
        skipLog: WarmupSkipLog,
        stream: String,
    ): OrderRequest? {
        val l1 = requireNotNull(compiledOcoLeg1) { "OCO requires STOP leg" }
        val l2 = requireNotNull(compiledOcoLeg2) { "OCO requires LIMIT leg" }
        val exitSide = if (side == Side.BUY) Side.SELL else Side.BUY
        val stopPrice =
            l1.evaluate(ctx, side, entry, stopDistance = null)
                ?: run {
                    skipLog.skipped("OCO stop price", ctx, stream)
                    return null
                }
        val limitPrice =
            l2.evaluate(ctx, side, entry, stopDistance = null)
                ?: run {
                    skipLog.skipped("OCO limit price", ctx, stream)
                    return null
                }
        val stopLeg =
            OrderRequest.Stop(
                id = ids.next(),
                symbol = symbol,
                side = exitSide,
                quantity = qty,
                stopPrice = stopPrice,
                timeInForce = tif,
                timestamp = ts,
            )
        val limitLeg =
            OrderRequest.Limit(
                id = ids.next(),
                symbol = symbol,
                side = exitSide,
                quantity = qty,
                limitPrice = limitPrice,
                timeInForce = tif,
                timestamp = ts,
            )
        return OrderRequest.StandaloneOCO(
            id = ids.next(),
            symbol = symbol,
            side = side,
            quantity = qty,
            leg1 = stopLeg,
            leg2 = limitLeg,
            timeInForce = tif,
            timestamp = ts,
        )
    }
}
