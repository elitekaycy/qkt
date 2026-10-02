package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal

/** The value of [expr] when it is a number literal, negated or not (`3`, `-3`); null for anything else. */
internal fun numericLiteral(expr: ExprAst): BigDecimal? =
    when {
        expr is NumLit -> expr.value
        expr is UnaryOp && expr.op == UnOp.NEG && expr.arg is NumLit -> expr.arg.value.negate()
        else -> null
    }
