package com.qkt.dsl.ast

data class ActionOpts(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val bracket: BracketAst? = null,
    val oco: OcoAst? = null,
)

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

data class SizeRiskAbs(
    val usd: ExprAst,
) : SizingAst

data class SizePositionFull(
    val stream: String,
) : SizingAst

sealed interface OrderTypeAst

data object Market : OrderTypeAst

data class Limit(
    val price: ExprAst,
) : OrderTypeAst

data class Stop(
    val price: ExprAst,
) : OrderTypeAst

data class StopLimit(
    val stopPrice: ExprAst,
    val limitPrice: ExprAst,
) : OrderTypeAst

data class TrailingBy(
    val distance: ExprAst,
) : OrderTypeAst

data class TrailingPct(
    val frac: ExprAst,
) : OrderTypeAst

sealed interface ChildPriceAst

data class ChildAt(
    val price: ExprAst,
) : ChildPriceAst

data class ChildBy(
    val distance: ExprAst,
) : ChildPriceAst

data class ChildPct(
    val frac: ExprAst,
) : ChildPriceAst

data class ChildRr(
    val multiplier: ExprAst,
) : ChildPriceAst

data class BracketAst(
    val stopLoss: ChildPriceAst? = null,
    val takeProfit: ChildPriceAst? = null,
)

data class OcoAst(
    val stop: ChildPriceAst,
    val limit: ChildPriceAst,
)

sealed interface TifAst

data object Gtc : TifAst

data object Ioc : TifAst

data object Fok : TifAst

data object Day : TifAst

data class Gtd(
    val until: ExprAst,
) : TifAst
