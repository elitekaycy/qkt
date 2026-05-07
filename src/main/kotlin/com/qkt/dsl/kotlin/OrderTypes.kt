package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

val market: OrderTypeAst = Market

fun limitAt(price: ExprAst): OrderTypeAst = Limit(price)

fun stopAt(price: ExprAst): OrderTypeAst = Stop(price)

fun stopLimit(
    stop: ExprAst,
    limit: ExprAst,
): OrderTypeAst = StopLimit(stop, limit)

fun trailingBy(distance: ExprAst): OrderTypeAst = TrailingBy(distance)

fun trailingPct(frac: ExprAst): OrderTypeAst = TrailingPct(frac)
