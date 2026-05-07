package com.qkt.dsl.ast

import java.math.BigDecimal

sealed interface ExprAst

data class NumLit(
    val value: BigDecimal,
) : ExprAst

data class BoolLit(
    val value: Boolean,
) : ExprAst

data class Ref(
    val name: String,
    val snapshot: SnapshotKind? = null,
) : ExprAst

data class StreamFieldRef(
    val stream: String,
    val field: String,
) : ExprAst

data class IndicatorCall(
    val name: String,
    val args: List<ExprAst>,
) : ExprAst

data class BinaryOp(
    val op: BinOp,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class UnaryOp(
    val op: UnOp,
    val arg: ExprAst,
) : ExprAst

data class CmpOp(
    val op: Cmp,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class Between(
    val v: ExprAst,
    val lo: ExprAst,
    val hi: ExprAst,
) : ExprAst

data class InList(
    val v: ExprAst,
    val members: List<ExprAst>,
) : ExprAst

data class Crosses(
    val direction: CrossDir,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class CaseWhen(
    val branches: List<Pair<ExprAst, ExprAst>>,
    val elseExpr: ExprAst,
) : ExprAst

data class Aggregate(
    val fn: AggFn,
    val series: ExprAst,
    val window: Window,
) : ExprAst

data class AccountRef(
    val field: String,
) : ExprAst

data class PositionRef(
    val stream: String,
) : ExprAst

data class StateAccessor(
    val source: StateSource,
    val key: String,
) : ExprAst

data class FuncCall(
    val name: String,
    val args: List<ExprAst>,
) : ExprAst
