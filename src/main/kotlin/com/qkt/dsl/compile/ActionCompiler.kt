package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Money
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.execution.At
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
            is Cancel -> compileCancel(action.stream)
            is CancelAll -> compileCancelAll()
            else -> error("Action ${action::class.simpleName} is not supported in 11d1")
        }

    private fun compileCloseAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            val out = mutableListOf<Signal>()
            for (streamAlias in ctx.streams.keys) {
                val sym = ctx.streams[streamAlias]?.symbol ?: continue
                out.add(Signal.CancelPendingForSymbol(sym))
            }
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
            val signals = mutableListOf<Signal>()
            signals.add(Signal.CancelPendingForSymbol(symbol))
            when {
                qty.signum() > 0 -> signals.add(Signal.Sell(symbol, qty))
                qty.signum() < 0 -> signals.add(Signal.Buy(symbol, qty.abs()))
            }
            signals
        }

    private fun compileCancel(streamAlias: String): (EvalContext) -> List<Signal> =
        { ctx ->
            val symbol = ctx.streams[streamAlias]?.symbol ?: error("Unknown stream alias: $streamAlias")
            listOf(Signal.CancelPendingForSymbol(symbol))
        }

    private fun compileCancelAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            ctx.streams.values
                .map { it.symbol }
                .distinct()
                .map { Signal.CancelPendingForSymbol(it) }
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
        // Stack path: STACK is mutually exclusive with BRACKET/OCO on the same action.
        if (opts.stack != null) {
            return compileStack(stream, opts, side)
        }

        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING")

        // Fast path: plain market + default TIF + no bracket/OCO/stack + direct qty sizing → emit Signal.Buy/Sell
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

    private fun compileStack(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val stackAst = opts.stack ?: error("unreachable")
        val tif = TifTranslator.translate(opts.tif)
        val staticStopDistance = resolveStaticStopDistance(opts.bracket?.stopLoss)
        val plan = StackCompiler.compile(stackAst, opts.sizing, opts.bracket, side)
        // Pre-compile one CompiledSize per layer (they may share the same sizing AST for
        // StackSpacing, but compiling per-layer is cheap and avoids sharing mutable state).
        val compiledSizes =
            plan.layers.map { layer ->
                sizingCompiler.compile(layer.sizing, staticStopDistance)
            }

        return { ctx ->
            val symbol = ctx.streams[stream]?.symbol ?: error("Unknown stream alias: $stream")
            val ts = ctx.strategyContext.clock.now()
            val currentPrice = ctx.candle.close

            // Resolve each layer's quantity now, using the candle close as the entry proxy.
            // Approximation: equity/balance at action-execute time may differ from fire time.
            // For risk-fraction strategies the difference is negligible tick-to-tick.
            val resolvedLayers =
                plan.layers.mapIndexed { idx, layer ->
                    val expectedEntry =
                        if (layer.trigger == com.qkt.execution.Immediate) {
                            currentPrice
                        } else {
                            val at = layer.trigger as At
                            evaluateLayerTriggerPrice(at.price, currentPrice)
                        }
                    val qty = compiledSizes[idx].evaluate(ctx, expectedEntry)
                    layer.copy(resolvedQuantity = qty)
                }
            val resolvedPlan = plan.copy(layers = resolvedLayers)
            val totalQty = resolvedLayers.sumOf { it.resolvedQuantity!! }

            val req =
                OrderRequest.Stack(
                    id = ids.next(),
                    symbol = symbol,
                    side = side,
                    quantity = totalQty.max(BigDecimal.ONE.movePointLeft(Money.SCALE)),
                    plan = resolvedPlan,
                    timeInForce = tif,
                    timestamp = ts,
                )
            listOf(Signal.Submit(req))
        }
    }

    // Evaluates a layer trigger expression using currentPrice as the anchor proxy.
    // Mirrors OrderManager.evaluateAt but lives here for compile-time resolution.
    private fun evaluateLayerTriggerPrice(
        expr: ExprAst,
        anchor: BigDecimal,
    ): BigDecimal =
        when (expr) {
            is StackEntryRef -> anchor
            is NumLit -> expr.value
            is BinaryOp -> {
                val l = evaluateLayerTriggerPrice(expr.lhs, anchor)
                val r = evaluateLayerTriggerPrice(expr.rhs, anchor)
                when (expr.op) {
                    BinOp.ADD -> l + r
                    BinOp.SUB -> l - r
                    BinOp.MUL -> l * r
                    BinOp.DIV -> l.divide(r, Money.CONTEXT)
                    else -> error("unsupported op in stack trigger: ${expr.op}")
                }
            }
            else -> error("unsupported trigger expression type: ${expr::class.simpleName}")
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
