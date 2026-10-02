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
        numericLiteral(o.distance)?.let { require(it.signum() > 0) { "TRAILING BY must be greater than 0, was $it" } }
        val distEval = exprCompiler.compile(o.distance)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                // A computed distance that is not positive skips the order rather than stopping the run.
                val d = distEval.evaluateNumber(ec)?.takeIf { it.signum() > 0 } ?: return@BuildRequest null
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
        numericLiteral(o.percent)?.let {
            require(
                it.signum() > 0 && it < HUNDRED,
            ) { "TRAILING PCT must be greater than 0 and less than 100, was $it" }
        }
        val percentEval = exprCompiler.compile(o.percent)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                // A computed percentage out of range skips the order rather than stopping the run.
                val percent =
                    percentEval.evaluateNumber(ec)?.takeIf { it.signum() > 0 && it < HUNDRED }
                        ?: return@BuildRequest null
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

    private companion object {
        val HUNDRED = BigDecimal("100")
    }
}
