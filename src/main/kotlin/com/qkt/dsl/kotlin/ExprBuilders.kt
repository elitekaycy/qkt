package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal

val Int.bd: ExprAst get() = NumLit(BigDecimal(this))
val Long.bd: ExprAst get() = NumLit(BigDecimal(this))
val Double.bd: ExprAst get() = NumLit(BigDecimal(this.toString()))
val String.bd: ExprAst get() = NumLit(BigDecimal(this))
val BigDecimal.bd: ExprAst get() = NumLit(this)

operator fun ExprAst.plus(other: ExprAst): ExprAst = BinaryOp(BinOp.ADD, this, other)

operator fun ExprAst.minus(other: ExprAst): ExprAst = BinaryOp(BinOp.SUB, this, other)

operator fun ExprAst.times(other: ExprAst): ExprAst = BinaryOp(BinOp.MUL, this, other)

operator fun ExprAst.div(other: ExprAst): ExprAst = BinaryOp(BinOp.DIV, this, other)

operator fun ExprAst.unaryMinus(): ExprAst = UnaryOp(UnOp.NEG, this)

infix fun ExprAst.gt(other: ExprAst): ExprAst = CmpOp(Cmp.GT, this, other)

infix fun ExprAst.lt(other: ExprAst): ExprAst = CmpOp(Cmp.LT, this, other)

infix fun ExprAst.gte(other: ExprAst): ExprAst = CmpOp(Cmp.GE, this, other)

infix fun ExprAst.lte(other: ExprAst): ExprAst = CmpOp(Cmp.LE, this, other)

infix fun ExprAst.eq(other: ExprAst): ExprAst = CmpOp(Cmp.EQ, this, other)

infix fun ExprAst.neq(other: ExprAst): ExprAst = CmpOp(Cmp.NE, this, other)

infix fun ExprAst.and(other: ExprAst): ExprAst = BinaryOp(BinOp.AND, this, other)

infix fun ExprAst.or(other: ExprAst): ExprAst = BinaryOp(BinOp.OR, this, other)

fun not(arg: ExprAst): ExprAst = UnaryOp(UnOp.NOT, arg)

fun ExprAst.between(
    lo: ExprAst,
    hi: ExprAst,
): ExprAst = Between(this, lo, hi)

fun ExprAst.inList(vararg members: ExprAst): ExprAst = InList(this, members.toList())

infix fun ExprAst.crossesAbove(other: ExprAst): ExprAst = Crosses(CrossDir.ABOVE, this, other)

infix fun ExprAst.crossesBelow(other: ExprAst): ExprAst = Crosses(CrossDir.BELOW, this, other)

fun caseWhen(
    vararg branches: Pair<ExprAst, ExprAst>,
    elseExpr: ExprAst,
): ExprAst = CaseWhen(branches.toList(), elseExpr)
