package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.strategy.Signal
import java.math.BigDecimal

class ActionCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(action: ActionAst): (EvalContext) -> List<Signal> =
        when (action) {
            is Buy -> compileBuySell(action.stream, action.opts) { sym, qty -> Signal.Buy(sym, qty) }
            is Sell -> compileBuySell(action.stream, action.opts) { sym, qty -> Signal.Sell(sym, qty) }
            else -> error("Action ${action::class.simpleName} is not supported in 11b")
        }

    private fun compileBuySell(
        stream: String,
        opts: ActionOpts,
        ctor: (String, BigDecimal) -> Signal,
    ): (EvalContext) -> List<Signal> {
        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING in 11b")
        require(sizing is SizeQty) { "Only direct quantity sizing is supported in 11b" }
        require(opts.orderType == null || opts.orderType == Market) {
            "Only MARKET order type is supported in 11b"
        }
        require(opts.tif == null) { "TIF is not supported in 11b" }
        require(opts.bracket == null) { "BRACKET is not supported in 11b" }
        require(opts.oco == null) { "OCO is not supported in 11b" }
        val qtyExpr = exprCompiler.compile(sizing.expr)
        return { ctx ->
            val symbol = ctx.streamSymbols[stream] ?: error("Unknown stream alias: $stream")
            val v = qtyExpr.evaluate(ctx)
            require(v is Value.Num) { "SIZING must be numeric, got $v" }
            listOf(ctor(symbol, v.v))
        }
    }
}
