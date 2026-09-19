package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.execution.At
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Compiles a `STACK` (pyramiding) `BUY`/`SELL` into one [OrderRequest.Stack]: every layer's
 * quantity is resolved at fire time and the outer bracket's indicator terms are frozen.
 */
internal class StackActionCompiler(
    private val childPriceResolver: ChildPriceResolver,
    private val childPriceFreezer: ChildPriceFreezer,
    private val sizingCompiler: SizingCompiler,
    private val ids: IdGenerator,
) {
    fun compile(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val stackAst = opts.stack ?: error("unreachable")
        val tif = TifTranslator.translate(opts.tif)
        val staticStopDistance = resolveStaticStopDistance(opts.bracket?.stopLoss)
        val compiledStopLoss =
            opts.bracket?.stopLoss?.let { childPriceResolver.compileStopLoss(it, allowExpressionDistances = false) }
        val frozenOuterSL = opts.bracket?.stopLoss?.let { childPriceFreezer.prepare(it) }
        val frozenOuterTP = opts.bracket?.takeProfit?.let { childPriceFreezer.prepare(it) }
        val plan = StackCompiler.compile(stackAst, opts.sizing, opts.bracket, side)
        // Pre-compile one CompiledSize per layer (they may share the same sizing AST for
        // StackSpacing, but compiling per-layer is cheap and avoids sharing mutable state).
        val compiledSizes =
            plan.layers.map { layer ->
                sizingCompiler.compile(
                    layer.sizing,
                    staticStopDistance,
                    stream,
                    runtimeStopDistanceAvailable = compiledStopLoss != null,
                )
            }

        return stack@{ ctx ->
            val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
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
                    val runtimeStopDistance =
                        compiledStopLoss?.let { stopLoss ->
                            val spec =
                                resolveStopLoss(stopLoss, ctx, side, expectedEntry)
                                    ?: return@stack emptyList()
                            stopDistance(expectedEntry, spec)
                        }
                    val qty = compiledSizes[idx].evaluate(ctx, expectedEntry, runtimeStopDistance)
                    layer.copy(resolvedQuantity = qty)
                }
            // Freeze indicator subexpressions in the outer bracket at fire time — layer
            // fills resolve these ASTs against the fill price and understand literal
            // arithmetic only. A null snapshot means the indicator is still warming up.
            val frozenOuterBracket =
                plan.outerBracket?.let { outer ->
                    val sl = frozenOuterSL?.freeze(ctx)
                    val tp = frozenOuterTP?.freeze(ctx)
                    if ((outer.stopLoss != null && sl == null) || (outer.takeProfit != null && tp == null)) {
                        return@stack emptyList()
                    }
                    outer.copy(stopLoss = sl, takeProfit = tp)
                }
            val resolvedPlan = plan.copy(layers = resolvedLayers, outerBracket = frozenOuterBracket)
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
}
