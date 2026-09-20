package com.qkt.dsl.ast

/** How an entry sizes itself: a quantity, notional, equity or balance fraction, risk, or the full position. */
sealed interface SizingAst

data class SizeQty(
    val expr: ExprAst,
) : SizingAst

data class SizeNotional(
    val usd: ExprAst,
) : SizingAst

data class SizePctEquity(
    val frac: ExprAst,
) : SizingAst

data class SizePctBalance(
    val frac: ExprAst,
) : SizingAst

data class SizeRiskFrac(
    val frac: ExprAst,
) : SizingAst

/**
 * Risk a fraction of the whole portfolio book (CAPITAL plus every child's realized PnL)
 * rather than this strategy's own equity. Only valid when deployed as a portfolio child.
 * e.g. `SIZING = 1.0 PCT RISK OF BOOK` on a $50k book risks $500 per trade.
 */
data class SizeRiskFracOfBook(
    val frac: ExprAst,
) : SizingAst

data class SizeRiskAbs(
    val usd: ExprAst,
) : SizingAst

data class SizePositionFull(
    val stream: String,
) : SizingAst
