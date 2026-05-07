package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.AccountRef
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
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.stdlib.FuncRegistry
import java.math.BigDecimal

class ExprCompiler(
    private val bindings: IndicatorBinding.Bag = IndicatorBinding.Bag(),
) {
    fun compile(expr: ExprAst): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            is BinaryOp -> compileBinary(expr)
            is UnaryOp -> compileUnary(expr)
            is CmpOp -> compileCmp(expr)
            is StreamFieldRef -> compileStreamField(expr)
            is IndicatorCall -> compileIndicator(expr)
            is AccountRef -> compileAccountRef(expr)
            is PositionRef -> compilePositionRef(expr)
            is StateAccessor -> compileStateAccessor(expr)
            is Between -> compileBetween(expr)
            is InList -> compileInList(expr)
            is CaseWhen -> compileCaseWhen(expr)
            is Crosses -> compileCrosses(expr)
            is FuncCall -> compileFuncCall(expr)
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }

    private fun compileFuncCall(call: FuncCall): CompiledExpr {
        require(FuncRegistry.has(call.name)) { "Unknown function: ${call.name}" }
        val args = call.args.map { compile(it) }
        return CompiledExpr { ctx ->
            val values = args.map { it.evaluate(ctx) }
            if (values.any { it !is Value.Num }) {
                Value.Undefined
            } else {
                Value.Num(FuncRegistry.invoke(call.name, values.map { (it as Value.Num).v }))
            }
        }
    }

    private fun compileCrosses(c: Crosses): CompiledExpr {
        val l = compile(c.lhs)
        val r = compile(c.rhs)
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

    private fun compileCaseWhen(expr: CaseWhen): CompiledExpr {
        val branches = expr.branches.map { compile(it.first) to compile(it.second) }
        val elseE = compile(expr.elseExpr)
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

    private fun compileInList(expr: InList): CompiledExpr {
        val v = compile(expr.v)
        val members = expr.members.map { compile(it) }
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

    private fun compileBetween(b: Between): CompiledExpr {
        val v = compile(b.v)
        val lo = compile(b.lo)
        val hi = compile(b.hi)
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
            val symbol = ctx.streamSymbols[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val qty = ctx.strategyContext.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            Value.Num(qty)
        }

    private fun compileStateAccessor(ref: StateAccessor): CompiledExpr {
        require(ref.source == StateSource.POSITION_AVG_PRICE) {
            "StateAccessor source ${ref.source} is not supported in 11c1"
        }
        return CompiledExpr { ctx ->
            val symbol = ctx.streamSymbols[ref.key] ?: error("Unknown stream alias: ${ref.key}")
            val price = ctx.strategyContext.positions.positionFor(symbol)?.avgEntryPrice ?: BigDecimal.ZERO
            Value.Num(price)
        }
    }

    private fun compileAccountRef(ref: AccountRef): CompiledExpr {
        require(ref.field in setOf("realized_pnl", "unrealized_pnl", "total_pnl")) {
            "Unsupported ACCOUNT field in 11c1: ${ref.field} (equity/balance/drawdown deferred — engine surface needs work)"
        }
        return CompiledExpr { ctx ->
            val pnl = ctx.strategyContext.pnl
            Value.Num(
                when (ref.field) {
                    "realized_pnl" -> pnl.realized()
                    "unrealized_pnl" -> pnl.unrealizedTotal()
                    "total_pnl" -> pnl.total()
                    else -> error("unreachable")
                },
            )
        }
    }

    private fun compileIndicator(call: IndicatorCall): CompiledExpr {
        val binding = bindings.bind(call)
        return CompiledExpr {
            val v = binding.indicator.value()
            if (v == null || !binding.indicator.isReady) Value.Undefined else Value.Num(v)
        }
    }

    private fun compileStreamField(ref: StreamFieldRef): CompiledExpr {
        require(ref.field in setOf("close", "open", "high", "low", "volume", "price")) {
            "Unknown stream field for ${ref.stream}: ${ref.field}"
        }
        return CompiledExpr { ctx ->
            val symbol = ctx.streamSymbols[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            if (ctx.candle.symbol != symbol) {
                Value.Undefined
            } else {
                Value.Num(
                    when (ref.field) {
                        "close", "price" -> ctx.candle.close
                        "open" -> ctx.candle.open
                        "high" -> ctx.candle.high
                        "low" -> ctx.candle.low
                        "volume" -> ctx.candle.volume
                        else -> error("unreachable")
                    },
                )
            }
        }
    }

    private fun compileBinary(op: BinaryOp): CompiledExpr {
        val l = compile(op.lhs)
        val r = compile(op.rhs)
        return when (op.op) {
            BinOp.ADD -> numericBinary(l, r) { a, b -> a.add(b, Money.CONTEXT) }
            BinOp.SUB -> numericBinary(l, r) { a, b -> a.subtract(b, Money.CONTEXT) }
            BinOp.MUL -> numericBinary(l, r) { a, b -> a.multiply(b, Money.CONTEXT) }
            BinOp.DIV -> numericBinary(l, r) { a, b -> a.divide(b, Money.CONTEXT) }
            BinOp.AND -> booleanBinary(l, r) { a, b -> a && b }
            BinOp.OR -> booleanBinary(l, r) { a, b -> a || b }
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

    private fun booleanBinary(
        l: CompiledExpr,
        r: CompiledExpr,
        op: (Boolean, Boolean) -> Boolean,
    ): CompiledExpr =
        CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Bool || rv !is Value.Bool) Value.Undefined else Value.Bool(op(lv.v, rv.v))
        }

    private fun compileUnary(op: UnaryOp): CompiledExpr {
        val a = compile(op.arg)
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

    private fun compileCmp(op: CmpOp): CompiledExpr {
        val l = compile(op.lhs)
        val r = compile(op.rhs)
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
