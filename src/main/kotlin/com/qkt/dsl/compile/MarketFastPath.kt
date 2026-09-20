package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.SizeQty
import com.qkt.strategy.Signal

/**
 * Compiles the fast path of a plain `BUY`/`SELL`: market order, default TIF, no bracket, OCO or
 * stacks, and a direct quantity. It emits a bare [Signal.Buy] or [Signal.Sell] instead of a
 * [Signal.Submit] of a built order request.
 */
internal fun compileMarketFastPath(
    stream: String,
    sizing: SizeQty,
    side: Side,
    exprCompiler: ExprCompiler,
): (EvalContext) -> List<Signal> {
    val qtyExpr = exprCompiler.compile(sizing.expr)
    return { ctx ->
        val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
        val v = qtyExpr.evaluate(ctx)
        require(v is Value.Num) { "SIZING must be numeric, got $v" }
        val sig = if (side == Side.BUY) Signal.Buy(symbol, v.v) else Signal.Sell(symbol, v.v)
        listOf(sig)
    }
}
