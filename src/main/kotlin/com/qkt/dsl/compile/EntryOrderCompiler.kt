package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.execution.withDayExpiry
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * Compiles a single-order `BUY`/`SELL`: the plain-market fast path (a bare [Signal.Buy] or
 * [Signal.Sell]), or a [Signal.Submit] of a pending order, a `BRACKET`, or an exit `OCO`, with
 * an optional `TIF GTD` deadline, `STACK_AT` tiers registered on [pendingStacks], and an
 * `EXIT AFTER` hold that wraps the order in a fill-anchored [OrderRequest.TimeExit].
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
    fun compile(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING")

        // STACK_AT on an exit OCO is untested; reject loudly rather than attach tiers silently.
        require(!(opts.oco != null && opts.stackAts.isNotEmpty())) {
            "STACK_AT cannot be combined with OCO on the same action"
        }
        require(!(opts.oco != null && opts.exitAfter != null)) {
            "EXIT AFTER cannot be combined with an exit OCO on the same action"
        }
        val holdMs: Long? = opts.exitAfter?.millis

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
                holdMs == null &&
                sizing is SizeQty
        if (isFastPath) {
            return compileMarketFastPath(stream, sizing as SizeQty, side, exprCompiler)
        }

        // Submit path: any non-trivial option → emit Signal.Submit(OrderRequest.X)
        require(opts.bracket == null || opts.oco == null) { "Cannot combine BRACKET and OCO on the same action" }
        // `OCO { STOP AT s, LIMIT AT t }` is the entry's exit pair stated as raw prices (actions.md):
        // the same two exits as `BRACKET { STOP_LOSS AT s, TAKE_PROFIT AT t }`, so it takes the
        // bracket path and its legs are placed reduce-only on the entry fill, one cancelling the other.
        val bracket: BracketAst? =
            opts.bracket ?: opts.oco?.let { BracketAst(stopLoss = it.stop, takeProfit = it.limit) }

        val tif = TifTranslator.translate(opts.tif)
        val orderType = opts.orderType ?: Market
        val compiledOrderType = orderTypeCompiler.compile(orderType, targetAlias = stream)

        val gtdDeadlineExpr = compileGtdDeadline(opts.tif, orderType, exprCompiler)

        val compiledSL = bracket?.stopLoss?.let { childPriceResolver.compileStopLoss(it) }
        val compiledTP = bracket?.takeProfit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }
        val frozenSL = bracket?.stopLoss?.let { childPriceFreezer.prepare(it) }
        val frozenTP = bracket?.takeProfit?.let { childPriceFreezer.prepare(it) }
        val staticStopDistance: BigDecimal? = resolveStaticStopDistance(bracket?.stopLoss)
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
                        if (ctx.isCrossStream(stream)) {
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
                if (bracket != null) {
                    when (val resolution = resolveBracket(compiledSL, compiledTP, ctx, side, entry, skipLog, stream)) {
                        is BracketResolution.Resolved -> resolution.bracket
                        BracketResolution.Undefined -> return@buySell emptyList()
                        is BracketResolution.Refused -> return@buySell listOf(
                            Signal.Suppressed(symbol, resolution.reason),
                        )
                    }
                } else {
                    null
                }
            val qty =
                compiledSize.evaluate(ctx, entry, resolvedBracket?.stopDistance)
                    ?: return@buySell emptyList<Signal>().also { skipLog.skipped("size", ctx, stream) }
            val entryReq =
                compiledOrderType.buildRequest.evaluate(ctx, ids.next(), symbol, side, qty, tif, "", ts)
                    ?: run {
                        skipLog.skipped("pending order price", ctx, stream)
                        return@buySell emptyList()
                    }

            val request: OrderRequest =
                when {
                    resolvedBracket != null -> {
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
                            takeProfit = resolvedBracket.takeProfit,
                            stopLoss = resolvedBracket.stopLoss,
                            takeProfitAst = frozenTP?.freeze(ctx),
                            stopLossAst = frozenSL?.freeze(ctx),
                            timeInForce = tif,
                            timestamp = ts,
                        )
                    }
                    else -> entryReq
                }

            val finalRequest: OrderRequest =
                (if (gtdDeadlineExpr != null) stampGtdDeadline(request, gtdDeadlineExpr, ctx) else request)
                    .withDayExpiry(ctx.strategyContext.calendar, ts)

            // Derived from the leg id, so a restart that resumes ids past its legs cannot re-mint it.
            val exitId = holdMs?.let { "${finalRequest.id}-exit" }
            if (stackAtTiers.isNotEmpty() && pendingStacks != null) {
                registerPendingStack(pendingStacks, finalRequest, symbol, side, stackAtTiers, holdMs, exitId)
            }

            val submitted =
                if (holdMs != null && exitId != null) timedExit(exitId, finalRequest, holdMs, ts) else finalRequest
            listOf(Signal.Submit(submitted))
        }
    }
}
