package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.Sell
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import org.slf4j.Logger

/**
 * Compiles an OTO (`ON_FILL`) parent: a `BUY`/`SELL` whose children are held by the order manager
 * until the parent fills. The children price themselves from the parent fill through `entry`.
 */
internal class OtoActionCompiler(
    private val orderTypeCompiler: OrderTypeCompiler,
    private val sizingCompiler: SizingCompiler,
    private val ids: IdGenerator,
    private val baskets: Map<String, List<String>>,
    private val strategyLogger: Logger,
) {
    /** A compiled OTO child: builds one child [OrderRequest] given the parent's fill context. */
    private fun interface CompiledOtoChild {
        fun build(
            childCtx: EvalContext,
            ts: Long,
        ): OrderRequest?
    }

    /**
     * Compile an OTO parent: a BUY/SELL whose `ON_FILL` children are placed only once the parent
     * fills (One-Triggers-Other). Emits a single [OrderRequest.OTO]; [com.qkt.app.OrderManager]
     * submits the parent, holds the children until it fills, and drops them if it never does.
     *
     * v1 keeps the parent plain (no BRACKET/OCO/STACK on the same action) and the children plain
     * BUY/SELL orders. A child prices itself relative to the parent fill via the `entry` keyword —
     * exact for a LIMIT/STOP parent (it fills at its price), the signal-time estimate for a MARKET
     * parent. e.g. `BUY gold SIZING 1 ON_FILL { SELL silver SIZING 1 }` market-hedges on fill.
     */
    fun compile(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val v1 = "OTO (ON_FILL) parent on '$stream'"
        require(opts.bracket == null) { "$v1 cannot also carry a BRACKET in v1." }
        require(opts.oco == null) { "$v1 cannot also carry an OCO in v1." }
        require(opts.stack == null && opts.stackAts.isEmpty()) { "$v1 cannot also carry STACK/STACK_AT in v1." }
        require(opts.tif !is com.qkt.dsl.ast.Gtd) { "$v1 does not support TIF GTD in v1." }
        val sizing = opts.sizing ?: error("OTO parent BUY/SELL requires SIZING")
        val tif = TifTranslator.translate(opts.tif)
        val compiledOrderType = orderTypeCompiler.compile(opts.orderType ?: Market, targetAlias = stream)
        val compiledSize = sizingCompiler.compile(sizing, null, stream)
        val children = opts.onFill.map { compileChild(it) }
        val skipLog = WarmupSkipLog(strategyLogger)
        return oto@{ ctx ->
            val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
            val ts = ctx.strategyContext.clock.now()
            val entry =
                compiledOrderType.entryPrice.evaluate(ctx)
                    ?: run {
                        skipLog.skipped("OTO parent entry price", ctx, stream)
                        return@oto emptyList()
                    }
            val qty = compiledSize.evaluate(ctx, entry)
            val parentReq =
                compiledOrderType.buildRequest.evaluate(ctx, ids.next(), symbol, side, qty, tif, "", ts)
                    ?: run {
                        skipLog.skipped("OTO parent pending price", ctx, stream)
                        return@oto emptyList()
                    }
            val childCtx = ctx.withEntryPrice(entry)
            val childReqs = children.map { it.build(childCtx, ts) }
            if (childReqs.any { it == null }) {
                skipLog.skipped("OTO child pending price", ctx, stream)
                return@oto emptyList()
            }
            val oto =
                OrderRequest.OTO(
                    id = ids.next(),
                    symbol = symbol,
                    side = side,
                    quantity = qty,
                    parent = parentReq,
                    children = childReqs.filterNotNull(),
                    timeInForce = tif,
                    timestamp = ts,
                )
            listOf(Signal.Submit(oto))
        }
    }

    private fun compileChild(child: ActionAst): CompiledOtoChild {
        val childSide: Side
        val childStream: String
        val childOpts: ActionOpts
        when (child) {
            is Buy -> {
                childSide = Side.BUY
                childStream = child.stream
                childOpts = child.opts
            }
            is Sell -> {
                childSide = Side.SELL
                childStream = child.stream
                childOpts = child.opts
            }
            else -> error("ON_FILL children must be BUY or SELL actions; got ${child::class.simpleName}")
        }
        require(childOpts.onFill.isEmpty()) { "ON_FILL children cannot nest their own ON_FILL (no nested OTO in v1)." }
        require(childOpts.bracket == null && childOpts.oco == null) {
            "ON_FILL children cannot carry a BRACKET or OCO in v1."
        }
        require(childOpts.stack == null && childOpts.stackAts.isEmpty()) {
            "ON_FILL children cannot carry STACK/STACK_AT in v1."
        }
        require(childOpts.tif == null) { "ON_FILL children cannot carry a TIF in v1." }
        require(baskets[childStream] == null) { "ON_FILL children cannot target a BASKET in v1." }
        val childSizing = childOpts.sizing ?: error("ON_FILL child BUY/SELL requires SIZING")
        val compiledOrderType = orderTypeCompiler.compile(childOpts.orderType ?: Market, targetAlias = childStream)
        val compiledSize = sizingCompiler.compile(childSizing, null, childStream)
        val childTif = TifTranslator.translate(null)
        return CompiledOtoChild { childCtx, ts ->
            val sym = childCtx.streams[childStream]?.qktSymbol ?: error("Unknown stream alias: $childStream")
            val childEntry = compiledOrderType.entryPrice.evaluate(childCtx) ?: return@CompiledOtoChild null
            val childQty = compiledSize.evaluate(childCtx, childEntry)
            compiledOrderType.buildRequest.evaluate(childCtx, ids.next(), sym, childSide, childQty, childTif, "", ts)
        }
    }
}
