package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
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
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
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
