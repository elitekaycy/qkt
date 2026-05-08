package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger = LoggerFactory.getLogger("com.qkt.dsl.strategy"),
    private val ids: IdGenerator = SequentialIdGenerator(prefix = "dsl-anonymous-"),
) {
    private val orderTypeCompiler = OrderTypeCompiler(exprCompiler)
    private val childPriceResolver = ChildPriceResolver(exprCompiler)
    private val sizingCompiler = SizingCompiler(exprCompiler)

    fun compile(action: ActionAst): (EvalContext) -> List<Signal> =
        when (action) {
            is Buy -> compileBuySell(action.stream, action.opts, Side.BUY)
            is Sell -> compileBuySell(action.stream, action.opts, Side.SELL)
            is Log -> compileLog(action)
            is Close -> compileClose(action.stream)
            is CloseAll -> compileCloseAll()
            is Cancel ->
                error(
                    "CANCEL action is deferred — engine cancellation API needs broker-side surface; revisit alongside Phase 11d order lifecycle work",
                )
            is CancelAll ->
                error(
                    "CANCEL_ALL action is deferred — engine cancellation API needs broker-side surface; revisit alongside Phase 11d order lifecycle work",
                )
            else -> error("Action ${action::class.simpleName} is not supported in 11d1")
        }

    private fun compileCloseAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            val out = mutableListOf<Signal>()
            for ((symbol, position) in ctx.strategyContext.positions.allPositions()) {
                val qty = position.quantity
                when {
                    qty.signum() > 0 -> out.add(Signal.Sell(symbol, qty))
                    qty.signum() < 0 -> out.add(Signal.Buy(symbol, qty.abs()))
                }
            }
            out
        }

    private fun compileClose(streamAlias: String): (EvalContext) -> List<Signal> =
        { ctx ->
            val symbol = ctx.streams[streamAlias]?.symbol ?: error("Unknown stream alias: $streamAlias")
            val qty =
                ctx.strategyContext.positions
                    .positionFor(symbol)
                    ?.quantity ?: BigDecimal.ZERO
            when {
                qty.signum() > 0 -> listOf(Signal.Sell(symbol, qty))
                qty.signum() < 0 -> listOf(Signal.Buy(symbol, qty.abs()))
                else -> emptyList()
            }
        }

    private fun compileLog(log: Log): (EvalContext) -> List<Signal> {
        val msg = log.message
        return { _ ->
            strategyLogger.info(msg)
            emptyList()
        }
    }

    private fun compileBuySell(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING")

        // Fast path: plain market + default TIF + no bracket/OCO + direct qty sizing → emit Signal.Buy/Sell
        val isFastPath =
            (opts.orderType == null || opts.orderType == Market) &&
                opts.tif == null &&
                opts.bracket == null &&
                opts.oco == null &&
                sizing is SizeQty
        if (isFastPath) {
            val qtyExpr = exprCompiler.compile((sizing as SizeQty).expr)
            return { ctx ->
                val symbol = ctx.streams[stream]?.symbol ?: error("Unknown stream alias: $stream")
                val v = qtyExpr.evaluate(ctx)
                require(v is Value.Num) { "SIZING must be numeric, got $v" }
                val sig = if (side == Side.BUY) Signal.Buy(symbol, v.v) else Signal.Sell(symbol, v.v)
                listOf(sig)
            }
        }

        // Submit path: any non-trivial option → emit Signal.Submit(OrderRequest.X)
        require(opts.bracket == null || opts.oco == null) { "Cannot combine BRACKET and OCO on the same action" }

        val tif = TifTranslator.translate(opts.tif)
        val orderType = opts.orderType ?: Market
        val compiledOrderType = orderTypeCompiler.compile(orderType)

        // Resolve a static stop distance for risk-based sizing, if possible
        val staticStopDistance: BigDecimal? = resolveStaticStopDistance(opts.bracket?.stopLoss)
        val compiledSize = sizingCompiler.compile(sizing, staticStopDistance)

        val compiledSL = opts.bracket?.stopLoss?.let { childPriceResolver.compile(it, ChildKind.STOP_LOSS) }
        val compiledTP = opts.bracket?.takeProfit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }
        val compiledOcoLeg1 = opts.oco?.stop?.let { childPriceResolver.compile(it, ChildKind.STOP_LOSS) }
        val compiledOcoLeg2 = opts.oco?.limit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }

        return { ctx ->
            val symbol = ctx.streams[stream]?.symbol ?: error("Unknown stream alias: $stream")
            val ts = ctx.strategyContext.clock.now()
            val entry = compiledOrderType.entryPrice.evaluate(ctx)
            val qty = compiledSize.evaluate(ctx, entry)
            val entryReq =
                compiledOrderType.buildRequest.evaluate(ctx, ids.next(), side, qty, tif, "", ts)

            val request: OrderRequest =
                when {
                    opts.bracket != null -> {
                        val sl = requireNotNull(compiledSL) { "BRACKET requires STOP LOSS" }
                        val tp = requireNotNull(compiledTP) { "BRACKET requires TAKE PROFIT" }
                        val slPrice = sl.evaluate(ctx, side, entry, stopDistance = null)
                        val sd = (entry - slPrice).abs()
                        val tpPrice = tp.evaluate(ctx, side, entry, stopDistance = sd)
                        OrderRequest.Bracket(
                            id = ids.next(),
                            symbol = symbol,
                            side = side,
                            quantity = qty,
                            entry = entryReq,
                            takeProfit = tpPrice,
                            stopLoss = slPrice,
                            timeInForce = tif,
                            timestamp = ts,
                        )
                    }
                    opts.oco != null -> {
                        val l1 = requireNotNull(compiledOcoLeg1) { "OCO requires STOP leg" }
                        val l2 = requireNotNull(compiledOcoLeg2) { "OCO requires LIMIT leg" }
                        val exitSide = if (side == Side.BUY) Side.SELL else Side.BUY
                        val stopPrice = l1.evaluate(ctx, side, entry, stopDistance = null)
                        val limitPrice = l2.evaluate(ctx, side, entry, stopDistance = null)
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
                        OrderRequest.StandaloneOCO(
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
                    else -> entryReq
                }

            listOf(Signal.Submit(request))
        }
    }

    private fun resolveStaticStopDistance(stop: ChildPriceAst?): BigDecimal? =
        when (stop) {
            is ChildBy -> {
                val expr = stop.distance
                if (expr is NumLit) expr.value else null
            }
            else -> null
        }
}
