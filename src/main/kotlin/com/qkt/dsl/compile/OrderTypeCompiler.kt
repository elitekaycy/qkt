package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import java.math.BigDecimal

data class CompiledOrderType(
    val buildRequest: BuildRequest,
    val entryPrice: EntryPriceRef,
)

fun interface EntryPriceRef {
    fun evaluate(ec: EvalContext): BigDecimal
}

fun interface BuildRequest {
    fun evaluate(
        ec: EvalContext,
        id: String,
        side: Side,
        qty: BigDecimal,
        tif: TimeInForce,
        strategyId: String,
        ts: Long,
    ): OrderRequest
}

class OrderTypeCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(ot: OrderTypeAst): CompiledOrderType =
        when (ot) {
            Market -> compileMarket()
            is Limit -> compileLimit(ot)
            is Stop -> compileStop(ot)
            is StopLimit -> compileStopLimit(ot)
            is TrailingBy -> compileTrailingBy(ot)
            is TrailingPct -> compileTrailingPct(ot)
        }

    private fun compileMarket(): CompiledOrderType {
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                OrderRequest.Market(
                    id = id,
                    symbol = ec.candle.symbol,
                    side = side,
                    quantity = qty,
                    timeInForce = tif,
                    timestamp = ts,
                    strategyId = strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }

    private fun compileLimit(o: Limit): CompiledOrderType {
        val priceEval = exprCompiler.compile(o.price)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val p = (priceEval.evaluate(ec) as Value.Num).v
                OrderRequest.Limit(id, ec.candle.symbol, side, qty, p, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (priceEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileStop(o: Stop): CompiledOrderType {
        val priceEval = exprCompiler.compile(o.price)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val p = (priceEval.evaluate(ec) as Value.Num).v
                OrderRequest.Stop(id, ec.candle.symbol, side, qty, p, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (priceEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileStopLimit(o: StopLimit): CompiledOrderType {
        val stopEval = exprCompiler.compile(o.stopPrice)
        val limitEval = exprCompiler.compile(o.limitPrice)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val sp = (stopEval.evaluate(ec) as Value.Num).v
                val lp = (limitEval.evaluate(ec) as Value.Num).v
                OrderRequest.StopLimit(id, ec.candle.symbol, side, qty, sp, lp, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (stopEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileTrailingBy(o: TrailingBy): CompiledOrderType {
        val distEval = exprCompiler.compile(o.distance)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val d = (distEval.evaluate(ec) as Value.Num).v
                OrderRequest.TrailingStop(
                    id, ec.candle.symbol, side, qty, d, TrailMode.ABSOLUTE, tif, ts, strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }

    private fun compileTrailingPct(o: TrailingPct): CompiledOrderType {
        val fracEval = exprCompiler.compile(o.frac)
        val build =
            BuildRequest { ec, id, side, qty, tif, strategyId, ts ->
                val f = (fracEval.evaluate(ec) as Value.Num).v
                val percent = f.multiply(BigDecimal("100"), Money.CONTEXT)
                OrderRequest.TrailingStop(
                    id, ec.candle.symbol, side, qty, percent, TrailMode.PERCENT, tif, ts, strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> ec.candle.close }
        return CompiledOrderType(build, entry)
    }
}
