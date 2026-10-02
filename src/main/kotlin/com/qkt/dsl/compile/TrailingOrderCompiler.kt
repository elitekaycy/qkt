package com.qkt.dsl.compile

import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequest
import com.qkt.execution.TrailMode
import java.math.BigDecimal

/** Compiles the trailing order types (`ORDER_TYPE = TRAILING BY` and `TRAILING PCT`) for [OrderTypeCompiler]. */
internal class TrailingOrderCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compileTrailingBy(o: TrailingBy): CompiledOrderType {
        val distEval = exprCompiler.compile(o.distance)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val d = distEval.evaluateNumber(ec) ?: return@BuildRequest null
                OrderRequest.TrailingStop(
                    id,
                    symbol,
                    side,
                    qty,
                    d,
                    TrailMode.ABSOLUTE,
                    tif,
                    ts,
                    strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }

    fun compileTrailingPct(o: TrailingPct): CompiledOrderType {
        val percentEval = exprCompiler.compile(o.percent)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val percent = percentEval.evaluateNumber(ec) ?: return@BuildRequest null
                require(percent.signum() > 0) { "TRAILING PCT must be greater than 0, was $percent" }
                require(percent < BigDecimal("100")) { "TRAILING PCT must be less than 100, was $percent" }
                OrderRequest.TrailingStop(
                    id,
                    symbol,
                    side,
                    qty,
                    percent,
                    TrailMode.PERCENT,
                    tif,
                    ts,
                    strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }

    private fun CompiledExpr.evaluateNumber(ec: EvalContext): BigDecimal? = (evaluate(ec) as? Value.Num)?.v
}
