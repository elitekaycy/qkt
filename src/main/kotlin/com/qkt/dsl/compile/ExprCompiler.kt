package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.stdlib.FuncRegistry
import java.math.BigDecimal

class ExprCompiler(
    private val bindings: IndicatorBinding.Bag = IndicatorBinding.Bag(),
    private val aggregates: AggregateBinding.Bag = AggregateBinding.Bag(),
) {
    fun compile(
        expr: ExprAst,
        ruleAlias: String? = null,
    ): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            is StringLit -> CompiledExpr { Value.Str(expr.value) }
            is BinaryOp -> compileBinary(expr, ruleAlias)
            is UnaryOp -> compileUnary(expr, ruleAlias)
            is CmpOp -> compileCmp(expr, ruleAlias)
            is StreamFieldRef -> compileStreamField(expr)
            is IndicatorCall -> compileIndicator(expr)
            is AccountRef -> compileAccountRef(expr)
            is PositionRef -> compilePositionRef(expr)
            is StateAccessor -> compileStateAccessor(expr)
            is Between -> compileBetween(expr, ruleAlias)
            is InList -> compileInList(expr, ruleAlias)
            is IsNull -> compileIsNull(expr, ruleAlias)
            is CaseWhen -> compileCaseWhen(expr, ruleAlias)
            is Crosses -> compileCrosses(expr, ruleAlias)
            is FuncCall -> compileFuncCall(expr, ruleAlias)
            is Ref -> compileRef(expr, ruleAlias)
            is Aggregate -> compileAggregate(expr, ruleAlias)
            is NowAccessor -> compileNow(expr)
            is com.qkt.dsl.ast.EntryQty ->
                error("ENTRY_QTY is only valid inside STACK_AT SIZING; got it in a non-STACK_AT expression")
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }

    private fun compileNow(acc: NowAccessor): CompiledExpr =
        CompiledExpr { ctx ->
            val nowMs = ctx.strategyContext.clock.now()
            when (acc.field) {
                NowField.EPOCH_MS -> Value.Num(BigDecimal.valueOf(nowMs))
                else -> {
                    val z =
                        java.time.Instant
                            .ofEpochMilli(nowMs)
                            .atZone(java.time.ZoneOffset.UTC)
                    val n =
                        when (acc.field) {
                            NowField.HOUR_UTC -> z.hour
                            NowField.MINUTE_UTC -> z.minute
                            NowField.WEEKDAY -> z.dayOfWeek.value - 1
                            NowField.DATE_UTC -> z.toLocalDate().toEpochDay().toInt()
                            NowField.EPOCH_MS -> error("handled above")
                        }
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            }
        }

    private fun compileAggregate(
        agg: Aggregate,
        ruleAlias: String?,
    ): CompiledExpr {
        val sym = ruleAlias ?: error("Aggregate requires rule symbol context")
        val state = AggregateBinding.Bag.stateFor(agg.fn, agg.window)
        val seriesEval = compile(agg.series, ruleAlias)
        val binding = AggregateBinding(seriesEval, agg.window, state, sym)
        aggregates.add(binding)
        return CompiledExpr {
            val v = state.read()
            if (v == null) Value.Undefined else Value.Num(v)
        }
    }

    private fun compileRef(
        ref: Ref,
        ruleAlias: String?,
    ): CompiledExpr {
        val kind =
            ref.snapshot
                ?: error("Bare Ref ${ref.name} should have been substituted by LetResolver")
        val sym = ruleAlias ?: error("Snapshot ref ${ref.name}@$kind requires rule symbol context")
        return when (kind) {
            is SnapshotTPast ->
                CompiledExpr { ctx ->
                    val v = ctx.snapshotStore.readRolling(sym, ref.name, kind.n)
                    if (v == null) Value.Undefined else Value.Num(v)
                }
            else ->
                CompiledExpr { ctx ->
                    val v = ctx.snapshotStore.readSlot(sym, ref.name, kind)
                    if (v == null) Value.Undefined else Value.Num(v)
                }
        }
    }

    private fun compileFuncCall(
        call: FuncCall,
        ruleAlias: String?,
    ): CompiledExpr {
        require(FuncRegistry.has(call.name)) { "Unknown function: ${call.name}" }
        val args = call.args.map { compile(it, ruleAlias) }
        return CompiledExpr { ctx ->
            val values = args.map { it.evaluate(ctx) }
            if (values.any { it !is Value.Num }) {
                Value.Undefined
            } else {
                val result = FuncRegistry.invoke(call.name, values.map { (it as Value.Num).v })
                if (result == null) Value.Undefined else Value.Num(result)
            }
        }
    }

    private fun compileCrosses(
        c: Crosses,
        ruleAlias: String?,
    ): CompiledExpr {
        val l = compile(c.lhs, ruleAlias)
        val r = compile(c.rhs, ruleAlias)
        val state = CrossesState()
        return CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Num || rv !is Value.Num) {
                Value.Undefined
            } else {
                val above = lv.v.compareTo(rv.v) > 0
                state.update(above, c.direction)
            }
        }
    }

    private fun compileCaseWhen(
        expr: CaseWhen,
        ruleAlias: String?,
    ): CompiledExpr {
        val branches = expr.branches.map { compile(it.first, ruleAlias) to compile(it.second, ruleAlias) }
        val elseE = compile(expr.elseExpr, ruleAlias)
        return CompiledExpr { ctx ->
            var result: Value? = null
            for ((cond, body) in branches) {
                val cv = cond.evaluate(ctx)
                if (cv is Value.Bool && cv.v) {
                    result = body.evaluate(ctx)
                    break
                }
            }
            result ?: elseE.evaluate(ctx)
        }
    }

    private fun compileInList(
        expr: InList,
        ruleAlias: String?,
    ): CompiledExpr {
        val v = compile(expr.v, ruleAlias)
        val members = expr.members.map { compile(it, ruleAlias) }
        return CompiledExpr { ctx ->
            val vv = v.evaluate(ctx)
            if (vv !is Value.Num) {
                Value.Undefined
            } else {
                var hit = false
                for (m in members) {
                    val mv = m.evaluate(ctx)
                    if (mv is Value.Num && mv.v.compareTo(vv.v) == 0) {
                        hit = true
                        break
                    }
                }
                Value.Bool(hit)
            }
        }
    }

    private fun compileIsNull(
        expr: IsNull,
        ruleAlias: String?,
    ): CompiledExpr {
        val inner = compile(expr.expr, ruleAlias)
        return CompiledExpr { ctx ->
            val v = inner.evaluate(ctx)
            val isUndef = v is Value.Undefined
            Value.Bool(if (expr.negated) !isUndef else isUndef)
        }
    }

    private fun compileBetween(
        b: Between,
        ruleAlias: String?,
    ): CompiledExpr {
        val v = compile(b.v, ruleAlias)
        val lo = compile(b.lo, ruleAlias)
        val hi = compile(b.hi, ruleAlias)
        return CompiledExpr { ctx ->
            val vv = v.evaluate(ctx)
            val lov = lo.evaluate(ctx)
            val hiv = hi.evaluate(ctx)
            if (vv !is Value.Num || lov !is Value.Num || hiv !is Value.Num) {
                Value.Undefined
            } else {
                Value.Bool(vv.v >= lov.v && vv.v <= hiv.v)
            }
        }
    }

    private fun compilePositionRef(ref: PositionRef): CompiledExpr =
        CompiledExpr { ctx ->
            val symbol = ctx.streams[ref.stream]?.qktSymbol ?: error("Unknown stream alias: ${ref.stream}")
            val qty =
                ctx.strategyContext.positions
                    .positionFor(symbol)
                    ?.quantity ?: BigDecimal.ZERO
            Value.Num(qty)
        }

    private fun compileStateAccessor(ref: StateAccessor): CompiledExpr =
        when (ref.source) {
            StateSource.POSITION_AVG_PRICE ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val price =
                        ctx.strategyContext.positions
                            .positionFor(symbol)
                            ?.avgEntryPrice ?: BigDecimal.ZERO
                    Value.Num(price)
                }
            // pnl = strategy-level realized + this-symbol unrealized.
            // Strategy-level realized isn't tracked per-symbol in StrategyPnL today;
            // a future enhancement could surface true per-symbol realized.
            StateSource.POSITION_PNL ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val realized = ctx.strategyContext.pnl.realized()
                    val unrealized = ctx.strategyContext.pnl.unrealizedFor(symbol)
                    Value.Num(realized.add(unrealized))
                }
            // Strategy-level realized P&L (not symbol-scoped). Per-symbol realized
            // requires lot-level accounting; see backlog.
            StateSource.POSITION_REALIZED_PNL ->
                CompiledExpr { ctx ->
                    Value.Num(ctx.strategyContext.pnl.realized())
                }
            StateSource.POSITION_UNREALIZED_PNL ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    Value.Num(ctx.strategyContext.pnl.unrealizedFor(symbol))
                }
            // holding_duration is SECONDS — the unit every shipped example and the DSL
            // reference assume. The clock is milliseconds internally; convert here so
            // `holding_duration > 7200` means "open for more than 2 hours".
            StateSource.POSITION_HOLDING_DURATION ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val openedAt =
                        ctx.strategyContext.positions
                            .positionFor(symbol)
                            ?.openedAt
                    val durationMs = if (openedAt == null) 0L else ctx.strategyContext.clock.now() - openedAt
                    Value.Num(BigDecimal.valueOf(durationMs).divide(MS_PER_SECOND, Money.CONTEXT))
                }
            StateSource.POSITION_MFE ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val mfe = ctx.strategyContext.positions.mfeFor(symbol) ?: BigDecimal.ZERO
                    Value.Num(mfe)
                }
            StateSource.POSITION_OPEN_COUNT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val n = ctx.strategyContext.positions.openCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_LONG_COUNT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val n = ctx.strategyContext.positions.longCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_SHORT_COUNT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val n = ctx.strategyContext.positions.shortCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_GROSS ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    Value.Num(ctx.strategyContext.positions.grossFor(symbol))
                }
            StateSource.POSITION_TRADES_TODAY ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val now = ctx.strategyContext.clock.now()
                    val n = ctx.strategyContext.tradeHistory.tradesTodayFor(symbol, now)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_LAST_TRADE_AT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    ctx.strategyContext.tradeHistory
                        .lastTradeAtFor(symbol)
                        ?.let { Value.Num(BigDecimal.valueOf(it)) } ?: Value.Undefined
                }
            else -> throw IllegalArgumentException("StateAccessor source ${ref.source} is not supported")
        }

    private fun compileAccountRef(ref: AccountRef): CompiledExpr {
        val pnlFields = setOf("realized_pnl", "unrealized_pnl", "total_pnl", "equity", "balance")
        val historyFields =
            setOf(
                "last_trade_at",
                "last_trade_pnl",
                "win_streak",
                "loss_streak",
                "trades_today",
                "wins_today",
                "losses_today",
            )
        val riskFields = setOf("dd_pct", "equity_peak", "open_positions_count")
        require(ref.field in pnlFields || ref.field in historyFields || ref.field in riskFields) {
            "Unsupported ACCOUNT field: ${ref.field}"
        }
        return CompiledExpr { ctx ->
            when (ref.field) {
                in pnlFields -> {
                    val pnl = ctx.strategyContext.pnl
                    Value.Num(
                        when (ref.field) {
                            "realized_pnl" -> pnl.realized()
                            "unrealized_pnl" -> pnl.unrealizedTotal()
                            "total_pnl" -> pnl.total()
                            "equity" -> pnl.equity()
                            "balance" -> pnl.balance()
                            else -> error("unreachable")
                        },
                    )
                }
                in historyFields -> {
                    val h = ctx.strategyContext.tradeHistory
                    val now = ctx.strategyContext.clock.now()
                    when (ref.field) {
                        "last_trade_at" -> h.lastTradeAt()?.let { Value.Num(BigDecimal.valueOf(it)) } ?: Value.Undefined
                        "last_trade_pnl" -> h.lastTradePnl()?.let { Value.Num(it) } ?: Value.Undefined
                        "win_streak" -> Value.Num(BigDecimal.valueOf(h.winStreak().toLong()))
                        "loss_streak" -> Value.Num(BigDecimal.valueOf(h.lossStreak().toLong()))
                        "trades_today" -> Value.Num(BigDecimal.valueOf(h.tradesToday(now).toLong()))
                        "wins_today" -> Value.Num(BigDecimal.valueOf(h.winsToday(now).toLong()))
                        "losses_today" -> Value.Num(BigDecimal.valueOf(h.lossesToday(now).toLong()))
                        else -> error("unreachable")
                    }
                }
                "dd_pct" -> {
                    // RiskView.drawdown is a fraction (0.05 = 5%); expose as percent for ergonomic
                    // condition writing: `WHEN ACCOUNT.dd_pct > 5 ...`.
                    Value.Num(
                        ctx.strategyContext.risk.drawdown
                            .multiply(BigDecimal("100")),
                    )
                }
                "equity_peak" -> Value.Num(ctx.strategyContext.risk.equityPeak)
                "open_positions_count" -> {
                    val count =
                        ctx.strategyContext.positions
                            .allPositions()
                            .size
                            .toLong()
                    Value.Num(BigDecimal.valueOf(count))
                }
                else -> error("unreachable")
            }
        }
    }

    private fun compileIndicator(call: IndicatorCall): CompiledExpr {
        val spec =
            com.qkt.dsl.stdlib.IndicatorRegistry
                .spec(call.name)
        val binding =
            if (spec != null && spec.seriesCount >= 2) {
                // #319 two-series (e.g. correlation): compile both series args and bind as a pair,
                // gated on the first series' stream. Each series may itself be a cross-stream expr.
                val primaryAlias =
                    streamAliasesIn(call.args[0]).firstOrNull()
                        ?: error("Indicator ${call.name} first series must reference a stream")
                bindings.bindPair(call, compile(call.args[0], null), compile(call.args[1], null), primaryAlias)
            } else {
                when (val seriesArg = call.args.firstOrNull()) {
                    is StreamFieldRef, is IndicatorCall, null -> bindings.bind(call)
                    else -> {
                        // #174 expression-fed: compile the series expression and bind via
                        // primary alias. Gate on the first StreamFieldRef the expression
                        // references; reject if it references no stream (caller mistake).
                        val primaryAlias =
                            streamAliasesIn(seriesArg).firstOrNull()
                                ?: error(
                                    "Indicator ${call.name} expression-fed series must reference at least one " +
                                        "stream (e.g. stddev(gold.close - silver.close, 60))",
                                )
                        bindings.bindExpression(call, compile(seriesArg, null), primaryAlias)
                    }
                }
            }
        return CompiledExpr {
            val v = binding.indicator.value()
            if (v == null || !binding.indicator.isReady) Value.Undefined else Value.Num(v)
        }
    }

    /**
     * Walks [expr] and returns the stream aliases it references via [StreamFieldRef],
     * in left-to-right traversal order with duplicates removed. Used by [compileIndicator]
     * to pick the primary alias for an expression-fed indicator binding (#174).
     */
    private fun streamAliasesIn(expr: ExprAst): List<String> {
        val out = LinkedHashSet<String>()

        fun walk(e: ExprAst) {
            when (e) {
                is StreamFieldRef -> out.add(e.stream)
                is BinaryOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is UnaryOp -> walk(e.arg)
                is CmpOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is Crosses -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is FuncCall -> for (a in e.args) walk(a)
                is IndicatorCall -> for (a in e.args) walk(a)
                is Aggregate -> walk(e.series)
                is Between -> {
                    walk(e.v)
                    walk(e.lo)
                    walk(e.hi)
                }
                is InList -> {
                    walk(e.v)
                    for (m in e.members) walk(m)
                }
                is CaseWhen -> {
                    for ((c, b) in e.branches) {
                        walk(c)
                        walk(b)
                    }
                    walk(e.elseExpr)
                }
                is IsNull -> walk(e.expr)
                else -> Unit
            }
        }
        walk(expr)
        return out.toList()
    }

    private fun compileStreamField(ref: StreamFieldRef): CompiledExpr {
        require(ref.field in CANDLE_FIELDS || ref.field in META_FIELDS) {
            "Unknown stream field for ${ref.stream}: ${ref.field}"
        }
        return if (ref.field in META_FIELDS) compileMetaField(ref) else compileCandleField(ref)
    }

    private fun compileCandleField(ref: StreamFieldRef): CompiledExpr =
        CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val candle =
                if (ctx.currentAlias == ref.stream ||
                    (ctx.currentAlias == null && ctx.candle.symbol == key.qktSymbol)
                ) {
                    ctx.candle
                } else {
                    ctx.hub.latest(key)
                }
            if (candle == null) {
                Value.Undefined
            } else {
                val fieldValue: BigDecimal? =
                    when (ref.field) {
                        // `value` is the macro-series accessor (MACRO:DGS10.value); a daily series has
                        // one number per day, which the candle path carries as the close.
                        "close", "price", "value" -> candle.close
                        "open" -> candle.open
                        "high" -> candle.high
                        "low" -> candle.low
                        "volume" -> candle.volume
                        "bid" -> candle.bid
                        "ask" -> candle.ask
                        "spread" -> candle.spread
                        else -> error("unreachable")
                    }
                if (fieldValue == null) Value.Undefined else Value.Num(fieldValue)
            }
        }

    private fun compileMetaField(ref: StreamFieldRef): CompiledExpr =
        CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val meta =
                ctx.strategyContext.instruments.lookup(key.qktSymbol)
                    ?: error("InstrumentMeta missing for ${key.qktSymbol} (covered by startup validation)")
            val value =
                when (ref.field) {
                    "tick_size" -> meta.pointSize
                    "contract_size" -> meta.contractSize
                    "volume_step" -> meta.volumeStep
                    "volume_min" -> meta.volumeMin
                    else -> error("unreachable: ${ref.field}")
                }
            Value.Num(value)
        }

    companion object {
        val CANDLE_FIELDS: Set<String> =
            setOf("close", "open", "high", "low", "volume", "price", "bid", "ask", "spread", "value")
        val META_FIELDS: Set<String> =
            setOf("tick_size", "contract_size", "volume_step", "volume_min")
        private val MS_PER_SECOND = BigDecimal(1000)
    }

    private fun compileBinary(
        op: BinaryOp,
        ruleAlias: String?,
    ): CompiledExpr {
        val l = compile(op.lhs, ruleAlias)
        val r = compile(op.rhs, ruleAlias)
        return when (op.op) {
            BinOp.ADD -> numericBinary(l, r) { a, b -> a.add(b, Money.CONTEXT) }
            BinOp.SUB -> numericBinary(l, r) { a, b -> a.subtract(b, Money.CONTEXT) }
            BinOp.MUL -> numericBinary(l, r) { a, b -> a.multiply(b, Money.CONTEXT) }
            BinOp.DIV -> numericBinary(l, r) { a, b -> a.divide(b, Money.CONTEXT) }
            BinOp.AND -> kleeneAnd(l, r)
            BinOp.OR -> kleeneOr(l, r)
        }
    }

    private fun numericBinary(
        l: CompiledExpr,
        r: CompiledExpr,
        op: (BigDecimal, BigDecimal) -> BigDecimal,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Num || rv !is Value.Num) Value.Undefined else Value.Num(op(lv.v, rv.v))
        }

    // Kleene three-valued logic: a side that is Undefined (warming indicator, missing
    // cross-stream bar) only poisons the result when it could change it. TRUE OR x is
    // TRUE whatever x turns out to be; FALSE AND x is FALSE. Without this, a session
    // gate like `warm_signal OR fallback` is silently suppressed for the whole warmup.
    private fun kleeneAnd(
        l: CompiledExpr,
        r: CompiledExpr,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            when {
                lv is Value.Bool && !lv.v -> Value.Bool(false)
                rv is Value.Bool && !rv.v -> Value.Bool(false)
                lv is Value.Bool && rv is Value.Bool -> Value.Bool(true)
                else -> Value.Undefined
            }
        }

    private fun kleeneOr(
        l: CompiledExpr,
        r: CompiledExpr,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            when {
                lv is Value.Bool && lv.v -> Value.Bool(true)
                rv is Value.Bool && rv.v -> Value.Bool(true)
                lv is Value.Bool && rv is Value.Bool -> Value.Bool(false)
                else -> Value.Undefined
            }
        }

    private fun compileUnary(
        op: UnaryOp,
        ruleAlias: String?,
    ): CompiledExpr {
        val a = compile(op.arg, ruleAlias)
        return when (op.op) {
            UnOp.NEG ->
                CompiledExpr { ctx ->
                    val v = a.evaluate(ctx)
                    if (v !is Value.Num) Value.Undefined else Value.Num(v.v.negate(Money.CONTEXT))
                }
            UnOp.NOT ->
                CompiledExpr { ctx ->
                    val v = a.evaluate(ctx)
                    if (v !is Value.Bool) Value.Undefined else Value.Bool(!v.v)
                }
        }
    }

    private fun compileCmp(
        op: CmpOp,
        ruleAlias: String?,
    ): CompiledExpr {
        val l = compile(op.lhs, ruleAlias)
        val r = compile(op.rhs, ruleAlias)
        return CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Num || rv !is Value.Num) {
                Value.Undefined
            } else {
                val c = lv.v.compareTo(rv.v)
                Value.Bool(
                    when (op.op) {
                        Cmp.GT -> c > 0
                        Cmp.LT -> c < 0
                        Cmp.GE -> c >= 0
                        Cmp.LE -> c <= 0
                        Cmp.EQ -> c == 0
                        Cmp.NE -> c != 0
                    },
                )
            }
        }
    }
}
