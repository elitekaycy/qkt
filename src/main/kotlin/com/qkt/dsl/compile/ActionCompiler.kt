package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Money
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.execution.At
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.withExpiresAt
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger = LoggerFactory.getLogger("com.qkt.dsl.strategy"),
    private val ids: IdGenerator = SequentialIdGenerator(prefix = "dsl-anonymous-"),
    private val pendingStacks: PendingStacks? = null,
    private val baskets: Map<String, List<String>> = emptyMap(),
) {
    private val orderTypeCompiler = OrderTypeCompiler(exprCompiler)
    private val childPriceResolver = ChildPriceResolver(exprCompiler)
    private val sizingCompiler = SizingCompiler(exprCompiler)
    private val latchCompiler = LatchCompiler(exprCompiler, sizingCompiler, ids)

    fun compile(action: ActionAst): (EvalContext) -> List<Signal> =
        when (action) {
            is Buy -> compileBuySell(action.stream, action.opts, Side.BUY)
            is Sell -> compileBuySell(action.stream, action.opts, Side.SELL)
            is Log -> compileLog(action)
            is Close -> compileClose(action.stream)
            is CloseAll -> compileCloseAll()
            is Cancel -> compileCancel(action.stream)
            is CancelAll -> compileCancelAll()
            is Block -> compileBlock(action)
            is OcoEntry -> compileOcoEntry(action)
            is Latch -> { ec ->
                listOf(Signal.ArmLatch(latchCompiler.compile(action, ec.strategyContext.strategyId), ec))
            }
            else -> error("Action ${action::class.simpleName} is not supported in 11d1")
        }

    private fun compileOcoEntry(action: OcoEntry): (EvalContext) -> List<Signal> {
        val leg1Compiled = compile(action.leg1)
        val leg2Compiled = compile(action.leg2)
        return { ctx ->
            val sigs1 = leg1Compiled(ctx)
            val sigs2 = leg2Compiled(ctx)
            val req1 =
                (sigs1.singleOrNull() as? Signal.Submit)?.request
                    ?: error("OCO_ENTRY leg1 must compile to exactly one Signal.Submit, got $sigs1")
            val req2 =
                (sigs2.singleOrNull() as? Signal.Submit)?.request
                    ?: error("OCO_ENTRY leg2 must compile to exactly one Signal.Submit, got $sigs2")
            val oco =
                OrderRequest.StandaloneOCO(
                    id = ids.next(),
                    symbol = req1.symbol,
                    side = req1.side,
                    quantity = req1.quantity,
                    leg1 = req1,
                    leg2 = req2,
                    timeInForce = req1.timeInForce,
                    timestamp = ctx.strategyContext.clock.now(),
                )
            listOf(Signal.Submit(oco))
        }
    }

    private fun compileBlock(action: Block): (EvalContext) -> List<Signal> {
        val children = action.actions.map { compile(it) }
        return { ctx ->
            val out = mutableListOf<Signal>()
            for (child in children) out.addAll(child(ctx))
            out
        }
    }

    private fun compileCloseAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            val out = mutableListOf<Signal>()
            for (streamAlias in ctx.streams.keys) {
                val sym = ctx.streams[streamAlias]?.qktSymbol ?: continue
                out.add(Signal.CancelPendingForSymbol(sym))
            }
            val open = ctx.strategyContext.positions.allPositions()
            for (symbol in open.keys) {
                out.addAll(closeSignalsFor(ctx, symbol))
            }
            out
        }

    private fun compileClose(streamAlias: String): (EvalContext) -> List<Signal> {
        baskets[streamAlias]?.let { constituents ->
            // CLOSE on a basket flattens every constituent — one basket close, N real closes.
            return { ctx ->
                val signals = mutableListOf<Signal>()
                for (alias in constituents) {
                    val symbol = ctx.streams[alias]?.qktSymbol ?: error("Unknown basket constituent alias: $alias")
                    signals.add(Signal.CancelPendingForSymbol(symbol))
                    signals.addAll(closeSignalsFor(ctx, symbol))
                }
                signals
            }
        }
        return { ctx ->
            val symbol = ctx.streams[streamAlias]?.qktSymbol ?: error("Unknown stream alias: $streamAlias")
            val signals = mutableListOf<Signal>()
            signals.add(Signal.CancelPendingForSymbol(symbol))
            signals.addAll(closeSignalsFor(ctx, symbol))
            signals
        }
    }

    /**
     * Signals that flatten [symbol]. When the position is held as independent legs (e.g. a
     * filled straddle), each leg is closed individually and attributed to its leg id — so a
     * net-zero pair still closes both sides, and on a hedging venue each close targets the
     * exact ticket instead of opening a counter. A plain single position (no independent legs)
     * keeps the net-quantity close. Both reach the same net position, so backtest == live.
     */
    private fun closeSignalsFor(
        ctx: EvalContext,
        symbol: String,
    ): List<Signal> {
        val legs = ctx.strategyContext.positions.legsFor(symbol)
        if (legs.any { it.role == com.qkt.positions.LegRole.INDEPENDENT }) {
            return legs.map { leg ->
                val exitSide = if (leg.side == Side.BUY) Side.SELL else Side.BUY
                Signal.Submit(
                    OrderRequest.Market(
                        id = ids.next(),
                        symbol = symbol,
                        side = exitSide,
                        quantity = leg.quantity,
                        timeInForce = TimeInForce.GTC,
                        timestamp = ctx.strategyContext.clock.now(),
                        closesTicket = leg.brokerTicket,
                        closesLegId = leg.legId,
                    ),
                )
            }
        }
        val qty =
            ctx.strategyContext.positions
                .positionFor(symbol)
                ?.quantity ?: BigDecimal.ZERO
        return when {
            qty.signum() > 0 -> listOf(Signal.Sell(symbol, qty))
            qty.signum() < 0 -> listOf(Signal.Buy(symbol, qty.abs()))
            else -> emptyList()
        }
    }

    private fun compileCancel(streamAlias: String): (EvalContext) -> List<Signal> =
        { ctx ->
            val symbol = ctx.streams[streamAlias]?.qktSymbol ?: error("Unknown stream alias: $streamAlias")
            listOf(Signal.CancelPendingForSymbol(symbol))
        }

    private fun compileCancelAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            ctx.streams.values
                .map { it.qktSymbol }
                .distinct()
                .map { Signal.CancelPendingForSymbol(it) }
        }

    private fun compileLog(log: Log): (EvalContext) -> List<Signal> {
        val placeholders = LOG_PLACEHOLDER_REGEX.findAll(log.messageFormat).map { it.groupValues[1] }.toSet()
        val unmatched = placeholders - log.fields.keys
        check(unmatched.isEmpty()) {
            "LOG placeholder(s) without matching field: ${unmatched.joinToString()}"
        }
        val compiledFields = log.fields.mapValues { (_, expr) -> exprCompiler.compile(expr) }
        return { ctx ->
            val resolved = compiledFields.mapValues { (_, ce) -> ce.evaluate(ctx) }
            val rendered = renderLogMessage(log.messageFormat, resolved)
            try {
                for ((k, v) in resolved) {
                    org.slf4j.MDC.put("log.$k", stringifyValue(v))
                }
                when (log.level) {
                    LogLevel.DEBUG -> strategyLogger.debug(rendered)
                    LogLevel.INFO -> strategyLogger.info(rendered)
                    LogLevel.WARN -> strategyLogger.warn(rendered)
                    LogLevel.ERROR -> strategyLogger.error(rendered)
                }
            } finally {
                for (k in resolved.keys) org.slf4j.MDC.remove("log.$k")
            }
            emptyList()
        }
    }

    private fun renderLogMessage(
        format: String,
        resolved: Map<String, Value>,
    ): String {
        var out = format
        for ((k, v) in resolved) {
            out = out.replace("{$k}", stringifyValue(v))
        }
        return out
    }

    private fun stringifyValue(v: Value): String =
        when (v) {
            is Value.Num -> v.v.toPlainString()
            is Value.Bool -> v.v.toString()
            is Value.Str -> v.v
            is Value.Undefined -> "undefined"
        }

    companion object {
        private val LOG_PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")
    }

    private fun compileBuySell(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        baskets[stream]?.let { constituents ->
            return compileBasketFanOut(stream, constituents, opts, side)
        }

        // OTO path: a parent with ON_FILL children placed only when the parent fills.
        if (opts.onFill.isNotEmpty()) {
            return compileOto(stream, opts, side)
        }

        // Stack path: STACK is mutually exclusive with BRACKET/OCO on the same action.
        if (opts.stack != null) {
            // Phase 27: STACK_AT cannot combine with STACK pyramiding — the runtime
            // would silently drop the conditional clauses. Reject loudly at compile time.
            require(opts.stackAts.isEmpty()) {
                "STACK_AT cannot be combined with STACK on the same action"
            }
            return compileStack(stream, opts, side)
        }

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
            val qtyExpr = exprCompiler.compile((sizing as SizeQty).expr)
            return { ctx ->
                val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
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
        val compiledOrderType = orderTypeCompiler.compile(orderType, targetAlias = stream)

        // Phase 38: compile the GTD deadline (if any) and reject GTD on Market actions.
        val gtdDeadlineExpr: CompiledExpr? =
            (opts.tif as? com.qkt.dsl.ast.Gtd)?.let { exprCompiler.compile(it.until) }
        if (gtdDeadlineExpr != null && orderType === Market) {
            error(
                "TIF GTD is only valid on pending order types (LIMIT/STOP/IFTOUCHED/...); " +
                    "MARKET orders fill instantly and have no expiry semantic.",
            )
        }

        // Resolve a static stop distance for risk-based sizing, if possible
        val staticStopDistance: BigDecimal? = resolveStaticStopDistance(opts.bracket?.stopLoss)
        val compiledSize = sizingCompiler.compile(sizing, staticStopDistance, stream)

        val compiledSL = opts.bracket?.stopLoss?.let { childPriceResolver.compileStopLoss(it) }
        val compiledTP = opts.bracket?.takeProfit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }
        val compiledOcoLeg1 = opts.oco?.stop?.let { childPriceResolver.compile(it, ChildKind.STOP_LOSS) }
        val compiledOcoLeg2 = opts.oco?.limit?.let { childPriceResolver.compile(it, ChildKind.TAKE_PROFIT) }

        return { ctx ->
            val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
            val ts = ctx.strategyContext.clock.now()
            val entry = compiledOrderType.entryPrice.evaluate(ctx)
            val qty = compiledSize.evaluate(ctx, entry)
            val entryReq =
                compiledOrderType.buildRequest.evaluate(ctx, ids.next(), symbol, side, qty, tif, "", ts)

            val request: OrderRequest =
                when {
                    opts.bracket != null -> {
                        val sl = requireNotNull(compiledSL) { "BRACKET requires STOP LOSS" }
                        val tp = requireNotNull(compiledTP) { "BRACKET requires TAKE PROFIT" }
                        val slSpec: com.qkt.execution.StopLossSpec =
                            when (sl) {
                                is CompiledStopLoss.Static -> sl.spec
                                is CompiledStopLoss.Dynamic -> sl.evaluate(ctx, side, entry)
                            }
                        // For RR take-profit, derive the stop distance from the Fixed-shaped
                        // stop. Armed trails carry their distance explicitly.
                        val sd: java.math.BigDecimal =
                            when (slSpec) {
                                is com.qkt.execution.StopLossSpec.Fixed -> (entry - slSpec.price).abs()
                                is com.qkt.execution.StopLossSpec.ArmedTrail -> slSpec.trailDistance
                            }
                        val tpPrice = tp.evaluate(ctx, side, entry, stopDistance = sd)
                        OrderRequest.Bracket(
                            id = ids.next(),
                            symbol = symbol,
                            side = side,
                            quantity = qty,
                            entry = entryReq,
                            takeProfit = tpPrice,
                            stopLoss = slSpec,
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

            // Phase 38: evaluate the GTD deadline (if any) and stamp expiresAt on the request,
            // propagating into nested sub-requests for composite shapes (Bracket.entry,
            // StandaloneOCO.leg1/leg2, OTO.parent/children, ScaleOut.basis).
            val finalRequest: OrderRequest =
                if (gtdDeadlineExpr != null) {
                    val deadline =
                        when (val r = gtdDeadlineExpr.evaluate(ctx)) {
                            is Value.Num -> r.v.toLong()
                            else ->
                                error(
                                    "TIF GTD expression evaluated to $r; expected a numeric epoch-millis timestamp",
                                )
                        }
                    request.withExpiresAt(deadline)
                } else {
                    request
                }

            if (stackAtTiers.isNotEmpty() && pendingStacks != null) {
                pendingStacks.register(
                    PendingStack(
                        parentClientOrderId = parentClientOrderIdFor(finalRequest),
                        symbol = symbol,
                        side = side,
                        tiers = stackAtTiers,
                        closeWatchIds = closeWatchIdsFor(finalRequest),
                    ),
                )
            }

            listOf(Signal.Submit(finalRequest))
        }
    }

    /** A compiled OTO child: builds one child [OrderRequest] given the parent's fill context. */
    private fun interface CompiledOtoChild {
        fun build(
            childCtx: EvalContext,
            ts: Long,
        ): OrderRequest
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
    private fun compileOto(
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
        val children = opts.onFill.map { compileOtoChild(it) }
        return { ctx ->
            val symbol = ctx.streams[stream]?.qktSymbol ?: error("Unknown stream alias: $stream")
            val ts = ctx.strategyContext.clock.now()
            val entry = compiledOrderType.entryPrice.evaluate(ctx)
            val qty = compiledSize.evaluate(ctx, entry)
            val parentReq = compiledOrderType.buildRequest.evaluate(ctx, ids.next(), symbol, side, qty, tif, "", ts)
            val childCtx = ctx.withEntryPrice(entry)
            val childReqs = children.map { it.build(childCtx, ts) }
            val oto =
                OrderRequest.OTO(
                    id = ids.next(),
                    symbol = symbol,
                    side = side,
                    quantity = qty,
                    parent = parentReq,
                    children = childReqs,
                    timeInForce = tif,
                    timestamp = ts,
                )
            listOf(Signal.Submit(oto))
        }
    }

    private fun compileOtoChild(child: ActionAst): CompiledOtoChild {
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
            val childEntry = compiledOrderType.entryPrice.evaluate(childCtx)
            val childQty = compiledSize.evaluate(childCtx, childEntry)
            compiledOrderType.buildRequest.evaluate(childCtx, ids.next(), sym, childSide, childQty, childTif, "", ts)
        }
    }

    /**
     * Fan a `BUY`/`SELL` on a basket alias out to one plain-market order per constituent,
     * each sized so its notional is `total / N` — equal economic weight, not equal lots, since
     * one lot of two differently-priced symbols is two different exposures. The basket has no
     * tradeable symbol of its own, so there is no single order to emit; each constituent order
     * routes by its own `qktSymbol`. e.g. `BUY antipodean SIZING NOTIONAL 10000` over
     * `[aud, nzd]` emits a BUY of $5,000 notional on each.
     *
     * Basket orders are plain market in v1: a BRACKET/OCO/TIF/STACK or a LIMIT/STOP type on a
     * basket order is a compile error, and the sizing must be `SIZING NOTIONAL` — the only mode
     * that yields a single economic total to split across constituents priced differently.
     */
    private fun compileBasketFanOut(
        basketAlias: String,
        constituents: List<String>,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val plain = "basket orders are plain market in v1"
        require(opts.bracket == null) { "BASKET order on '$basketAlias' cannot carry a BRACKET ($plain)." }
        require(opts.oco == null) { "BASKET order on '$basketAlias' cannot carry an OCO ($plain)." }
        require(opts.stack == null && opts.stackAts.isEmpty()) {
            "BASKET order on '$basketAlias' cannot carry STACK/STACK_AT ($plain)."
        }
        require(opts.tif == null) { "BASKET order on '$basketAlias' cannot carry a TIF ($plain)." }
        require(opts.orderType == null || opts.orderType == Market) {
            "BASKET order on '$basketAlias' must be a market order; LIMIT/STOP are not supported in v1."
        }
        val sizing = opts.sizing
        require(sizing is SizeNotional) {
            "BASKET order on '$basketAlias' requires notional sizing (SIZING <amount> USD); got " +
                "${sizing?.let { it::class.simpleName } ?: "no SIZING"} — each constituent is sized " +
                "to an equal share of the notional."
        }
        val notionalExpr = exprCompiler.compile(sizing.usd)
        val n = BigDecimal(constituents.size)
        return { ctx ->
            val total = notionalExpr.evaluate(ctx)
            require(total is Value.Num) { "BASKET SIZING NOTIONAL must be numeric, got $total" }
            val perConstituent = total.v.divide(n, Money.CONTEXT)
            constituents.map { alias ->
                val key = ctx.streams[alias] ?: error("Unknown basket constituent alias: $alias")
                val symbol = key.qktSymbol
                val price =
                    ctx.hub.latest(key)?.close
                        ?: error("BASKET '$basketAlias' constituent '$alias' has no price yet")
                val contractSize =
                    ctx.strategyContext.instruments
                        .require(symbol)
                        .contractSize
                val qty = perConstituent.divide(price.multiply(contractSize, Money.CONTEXT), Money.CONTEXT)
                if (side == Side.BUY) Signal.Buy(symbol, qty) else Signal.Sell(symbol, qty)
            }
        }
    }

    /**
     * The clientOrderId the broker echoes back on [com.qkt.events.BrokerEvent.OrderFilled]
     * for the parent leg's primary entry. For a plain Market submit it's the request id;
     * for a Bracket parent the broker fills the inner entry, so it's [OrderRequest.Bracket.entry.id].
     */
    private fun parentClientOrderIdFor(request: OrderRequest): String =
        when (request) {
            is OrderRequest.Bracket -> request.entry.id
            else -> request.id
        }

    /**
     * Predicted clientOrderIds whose fill signals that the parent leg has closed. For
     * Bracket parents this matches the deterministic naming in
     * [com.qkt.app.OrderManager.submitBracketFallback]: `<bracket-id>-tp` and `<bracket-id>-sl`.
     *
     * Phase 27 limitation: this covers the paper/backtest path where bracket-fallback
     * controls the child ids. Native broker brackets (e.g. MT5) and strategy-initiated
     * manual closes rely on leg-aware fill routing (a separate task).
     */
    private fun closeWatchIdsFor(request: OrderRequest): Set<String> =
        when (request) {
            is OrderRequest.Bracket -> setOf("${request.id}-tp", "${request.id}-sl")
            else -> emptySet()
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
                sizingCompiler.compile(layer.sizing, staticStopDistance, stream)
            }

        return { ctx ->
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
            is com.qkt.dsl.ast.ChildArmedTrail -> {
                // Armed trail's trail distance IS the worst-case stop distance for risk
                // sizing — pre-arm the stop sits at entry ± distance, post-arm the stop
                // trails by the same distance. Either way `SIZING RISK $ N` sees a
                // well-defined stop distance. See spec §6 and plan correction 5.
                val expr = stop.trailDistance
                if (expr is NumLit) expr.value else null
            }
            else -> null
        }
}
