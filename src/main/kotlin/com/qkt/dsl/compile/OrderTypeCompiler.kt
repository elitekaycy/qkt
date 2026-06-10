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
        symbol: String,
        side: Side,
        qty: BigDecimal,
        tif: TimeInForce,
        strategyId: String,
        ts: Long,
    ): OrderRequest
}

/**
 * Compiles a DSL order-type clause into a request builder. The order's symbol is the
 * caller-resolved TARGET stream's symbol, never the evaluating candle's — a rule
 * listening on gold whose action buys silver must order silver. [targetAlias] is the
 * action's stream alias, used to read the target's latest close for entry estimates.
 */
class OrderTypeCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(
        ot: OrderTypeAst,
        targetAlias: String? = null,
    ): CompiledOrderType =
        when (ot) {
            Market -> compileMarket(targetAlias)
            is Limit -> compileLimit(ot)
            is Stop -> compileStop(ot)
            is StopLimit -> compileStopLimit(ot)
            is TrailingBy -> compileTrailingBy(ot)
            is TrailingPct -> compileTrailingPct(ot)
        }

    /**
     * The latest close of [targetAlias]'s stream — the evaluating candle when it IS the
     * target, the hub's latest closed bar otherwise. Errors when the target has no bar
     * yet: sizing a cross-stream market order off the wrong instrument's price is the
     * silent alternative.
     */
    private fun targetClose(
        ec: EvalContext,
        targetAlias: String?,
    ): BigDecimal {
        if (targetAlias == null) return ec.candle.close
        val key = ec.streams[targetAlias] ?: error("Unknown stream alias: $targetAlias")
        if (ec.currentAlias == targetAlias ||
            (ec.currentAlias == null && ec.candle.symbol == key.qktSymbol)
        ) {
            return ec.candle.close
        }
        return ec.hub.latest(key)?.close
            ?: error("No closed bar yet for stream '$targetAlias' — cannot price its order")
    }

    private fun compileMarket(targetAlias: String?): CompiledOrderType {
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                OrderRequest.Market(
                    id = id,
                    symbol = symbol,
                    side = side,
                    quantity = qty,
                    timeInForce = tif,
                    timestamp = ts,
                    strategyId = strategyId,
                )
            }
        val entry = EntryPriceRef { ec -> targetClose(ec, targetAlias) }
        return CompiledOrderType(build, entry)
    }

    private fun compileLimit(o: Limit): CompiledOrderType {
        val priceEval = exprCompiler.compile(o.price)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val p = (priceEval.evaluate(ec) as Value.Num).v
                OrderRequest.Limit(id, symbol, side, qty, p, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (priceEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileStop(o: Stop): CompiledOrderType {
        val priceEval = exprCompiler.compile(o.price)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val p = (priceEval.evaluate(ec) as Value.Num).v
                OrderRequest.Stop(id, symbol, side, qty, p, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (priceEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileStopLimit(o: StopLimit): CompiledOrderType {
        val stopEval = exprCompiler.compile(o.stopPrice)
        val limitEval = exprCompiler.compile(o.limitPrice)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val sp = (stopEval.evaluate(ec) as Value.Num).v
                val lp = (limitEval.evaluate(ec) as Value.Num).v
                OrderRequest.StopLimit(id, symbol, side, qty, sp, lp, tif, ts, strategyId)
            }
        val entry = EntryPriceRef { ec -> (stopEval.evaluate(ec) as Value.Num).v }
        return CompiledOrderType(build, entry)
    }

    private fun compileTrailingBy(o: TrailingBy): CompiledOrderType {
        val distEval = exprCompiler.compile(o.distance)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val d = (distEval.evaluate(ec) as Value.Num).v
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

    private fun compileTrailingPct(o: TrailingPct): CompiledOrderType {
        val fracEval = exprCompiler.compile(o.frac)
        val build =
            BuildRequest { ec, id, symbol, side, qty, tif, strategyId, ts ->
                val f = (fracEval.evaluate(ec) as Value.Num).v
                val percent = f.multiply(BigDecimal("100"), Money.CONTEXT)
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
}
