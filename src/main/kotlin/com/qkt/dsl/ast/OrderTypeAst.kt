package com.qkt.dsl.ast

/** How an entry is priced: market, limit, stop, stop-limit or trailing. */
sealed interface OrderTypeAst

data object Market : OrderTypeAst

data class Limit(
    val price: ExprAst,
) : OrderTypeAst

/** Hook-only pending limit resolved from the closing fill and its side. */
data class ExitRelativeLimit(
    val price: DirRel,
) : OrderTypeAst

data class Stop(
    val price: ExprAst,
) : OrderTypeAst

/** Hook-only pending stop resolved from the closing fill and its side. */
data class ExitRelativeStop(
    val price: DirRel,
) : OrderTypeAst

data class StopLimit(
    val stopPrice: ExprAst,
    val limitPrice: ExprAst,
) : OrderTypeAst

data class TrailingBy(
    val distance: ExprAst,
) : OrderTypeAst

/** Trailing-stop distance in percentage points, so `1` means one percent. */
data class TrailingPct(
    val percent: ExprAst,
) : OrderTypeAst

/** Time in force for an order: GTC, IOC, FOK, DAY or good-till-date. */
sealed interface TifAst

data object Gtc : TifAst

data object Ioc : TifAst

data object Fok : TifAst

data object Day : TifAst

data class Gtd(
    val until: ExprAst,
) : TifAst
