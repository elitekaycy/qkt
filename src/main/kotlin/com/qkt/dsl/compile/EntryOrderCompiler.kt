package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * Compiles a single-order `BUY`/`SELL`: the plain-market fast path (a bare [Signal.Buy] or
 * [Signal.Sell]), or a [Signal.Submit] of a pending order, a `BRACKET`, or an exit `OCO`, with
 * an optional `TIF GTD` deadline and `STACK_AT` tiers registered on [pendingStacks].
 */
internal class EntryOrderCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger,
    private val ids: IdGenerator,
    private val pendingStacks: PendingStacks?,
    private val orderTypeCompiler: OrderTypeCompiler,
    private val childPriceResolver: ChildPriceResolver,
    private val childPriceFreezer: ChildPriceFreezer,
    private val sizingCompiler: SizingCompiler,
) {
    private val ocoExits = OcoExitOrderBuilder(ids)

    fun compile(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING")

        // Phase 27: STACK_AT on an OCO parent is silently broken — the OCO id is never
        // echoed back on a broker fill (the broker fills leg1.id or leg2.id), so the
        // engine would never be constructed. Reject loudly until OCO leg-id wiring lands.
        require(!(opts.oco != null && opts.stackAts.isNotEmpty())) {
            "STACK_AT cannot be combined with OCO on the same action"
        }

        // Pre-compile STACK_AT tiers if present so we can register them on each emit.
        val stackAtTiers: List<CompiledStackTier> =
            if (opts.stackAts.isNotEmpty()) StackAtCompiler.compileAll(opts.stackAts) else emptyList()

        // Fast path: plain market + default TIF + no bracket/OCO/stack/stack-at + direct qty sizing → emit Signal.Buy/Sell
        val isFastPath =
            (opts.orderType == null || opts.orderType == Market) &&
                opts.tif == null &&
                opts.bracket == null &&
                opts.oco == null &&
                stackAtTiers.isEmpty() &&
                sizing is SizeQty
        if (isFastPath) {
            return compileMarketFastPath(stream, sizing as SizeQty, side, exprCompiler)
        }

        // Submit path: any non-trivial option → emit Signal.Submit(OrderRequest.X)
        require(opts.bracket == null || opts.oco == null) { "Cannot combine BRACKET and OCO on the same action" }

        val tif = TifTranslator.translate(opts.tif)
        val orderType = opts.orderType ?: Market
        val compiledOrderType = orderTypeCompiler.compile(orderType, targetAlias = stream)

        val gtdDeadlineExpr = compileGtdDeadline(opts.tif, orderType, exprCompiler)

        val compiledSL = opts.bracket?.stopLoss?.let { childPriceResolver.compileStopLoss(it) }
        val compiledTP = opts.bracket?.takeProfit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }
        val frozenSL = opts.bracket?.stopLoss?.let { childPriceFreezer.prepare(it) }
        val frozenTP = opts.bracket?.takeProfit?.let { childPriceFreezer.prepare(it) }
        val compiledOcoLeg1 = opts.oco?.stop?.let { childPriceResolver.compile(it, ChildKind.STOP_LOSS) }
        val compiledOcoLeg2 = opts.oco?.limit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }
        val staticStopDistance: BigDecimal? = resolveStaticStopDistance(opts.bracket?.stopLoss)
        val compiledSize =
            sizingCompiler.compile(
                sizing,
                staticStopDistance,
                stream,
                runtimeStopDistanceAvailable = compiledSL != null,
            )

        val skipLog = WarmupSkipLog(strategyLogger)
        return buySell@{ ctx ->
            val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
            val ts = ctx.strategyContext.clock.now()
            val entry =
                compiledOrderType.entryPrice.evaluate(ctx)
                    ?: run {
                        skipLog.skipped("entry price", ctx, stream)
                        // A rule firing on one stream's bar can order on another, and that order
                        // prices itself from ITS OWN stream's last closed candle. Before that
                        // stream has closed one there is no price and the order cannot be built.
                        // Say so as a suppressed signal rather than returning nothing: a dropped
                        // cross-stream order is otherwise invisible in the trade record, and it
                        // is the kind of silence that costs a leg of a hedge without a trace.
                        // The same-stream case is ordinary warm-up and stays quiet.
                        if (crossStream(ctx, stream)) {
                            return@buySell listOf(
                                Signal.Suppressed(
                                    symbol = symbol,
                                    reason =
                                        "entry price for '$stream' is undefined: it has not closed a candle yet, " +
                                            "and this rule fired on another stream's bar. Declare WARMUP on '$stream'.",
                                ),
                            )
                        }
                        return@buySell emptyList()
                    }
            val resolvedBracket: ResolvedBracket? =
                if (opts.bracket != null) {
                    resolveBracket(compiledSL, compiledTP, ctx, side, entry, skipLog, stream)
                        ?: return@buySell emptyList()
                } else {
                    null
                }
            val qty = compiledSize.evaluate(ctx, entry, resolvedBracket?.stopDistance)
            val entryReq =
                compiledOrderType.buildRequest.evaluate(ctx, ids.next(), symbol, side, qty, tif, "", ts)
                    ?: run {
                        skipLog.skipped("pending order price", ctx, stream)
                        return@buySell emptyList()
                    }

            val request: OrderRequest =
                when {
                    opts.bracket != null -> {
                        val bracket = requireNotNull(resolvedBracket)
                        // Frozen ASTs snapshot indicator subexpressions at entry so OrderManager's
                        // fill-time re-anchor sees literal arithmetic only. The bracket prices
                        // resolved above from the same context, so a null snapshot cannot happen
                        // here; the elvis is a type-level fallback to those resolved prices.
                        OrderRequest.Bracket(
                            id = ids.next(),
                            symbol = symbol,
                            side = side,
                            quantity = qty,
                            entry = entryReq,
                            takeProfit = bracket.takeProfit,
                            stopLoss = bracket.stopLoss,
                            takeProfitAst = frozenTP?.freeze(ctx),
                            stopLossAst = frozenSL?.freeze(ctx),
                            timeInForce = tif,
                            timestamp = ts,
                        )
                    }
                    opts.oco != null ->
                        ocoExits.build(
                            compiledOcoLeg1,
                            compiledOcoLeg2,
                            ctx,
                            side,
                            entry,
                            symbol,
                            qty,
                            tif,
                            ts,
                            skipLog,
                            stream,
                        )
                            ?: return@buySell emptyList()
                    else -> entryReq
                }

            val finalRequest: OrderRequest =
                if (gtdDeadlineExpr != null) stampGtdDeadline(request, gtdDeadlineExpr, ctx) else request

            if (stackAtTiers.isNotEmpty() && pendingStacks != null) {
                registerPendingStack(pendingStacks, finalRequest, symbol, side, stackAtTiers)
            }

            listOf(Signal.Submit(finalRequest))
        }
    }

    /**
     * True when [stream] is not the stream whose bar is being evaluated — i.e. this action is
     * ordering on a symbol other than the one that triggered the rule.
     */
    private fun crossStream(
        ctx: EvalContext,
        stream: String,
    ): Boolean {
        val target = ctx.streams[stream] ?: return false
        val current = ctx.currentAlias
        return if (current != null) current != stream else ctx.candle.symbol != target.qktSymbol
    }
}
