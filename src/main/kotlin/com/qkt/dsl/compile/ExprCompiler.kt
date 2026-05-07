package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp

class ExprCompiler {
    fun compile(expr: ExprAst): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            is BinaryOp -> compileBinary(expr)
            is UnaryOp -> compileUnary(expr)
            is CmpOp -> compileCmp(expr)
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }

    private fun compileBinary(op: BinaryOp): CompiledExpr {
        val l = compile(op.lhs)
        val r = compile(op.rhs)
        return when (op.op) {
            BinOp.ADD ->
                CompiledExpr { ctx ->
                    Value.Num((l.evaluate(ctx) as Value.Num).v.add((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT))
                }
            BinOp.SUB ->
                CompiledExpr { ctx ->
                    Value.Num((l.evaluate(ctx) as Value.Num).v.subtract((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT))
                }
            BinOp.MUL ->
                CompiledExpr { ctx ->
                    Value.Num((l.evaluate(ctx) as Value.Num).v.multiply((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT))
                }
            BinOp.DIV ->
                CompiledExpr { ctx ->
                    Value.Num((l.evaluate(ctx) as Value.Num).v.divide((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT))
                }
            BinOp.AND ->
                CompiledExpr { ctx ->
                    Value.Bool((l.evaluate(ctx) as Value.Bool).v && (r.evaluate(ctx) as Value.Bool).v)
                }
            BinOp.OR ->
                CompiledExpr { ctx ->
                    Value.Bool((l.evaluate(ctx) as Value.Bool).v || (r.evaluate(ctx) as Value.Bool).v)
                }
        }
    }

    private fun compileUnary(op: UnaryOp): CompiledExpr {
        val a = compile(op.arg)
        return when (op.op) {
            UnOp.NEG -> CompiledExpr { ctx -> Value.Num((a.evaluate(ctx) as Value.Num).v.negate(Money.CONTEXT)) }
            UnOp.NOT -> CompiledExpr { ctx -> Value.Bool(!(a.evaluate(ctx) as Value.Bool).v) }
        }
    }

    private fun compileCmp(op: CmpOp): CompiledExpr {
        val l = compile(op.lhs)
        val r = compile(op.rhs)
        return CompiledExpr { ctx ->
            val lv = (l.evaluate(ctx) as Value.Num).v
            val rv = (r.evaluate(ctx) as Value.Num).v
            val c = lv.compareTo(rv)
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
