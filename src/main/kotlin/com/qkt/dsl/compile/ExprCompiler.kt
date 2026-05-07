package com.qkt.dsl.compile

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit

class ExprCompiler {
    fun compile(expr: ExprAst): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }
}
