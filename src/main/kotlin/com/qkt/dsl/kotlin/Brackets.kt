package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OcoAst

fun childAt(price: ExprAst): ChildPriceAst = ChildAt(price)

fun childBy(distance: ExprAst): ChildPriceAst = ChildBy(distance)

fun childPct(frac: ExprAst): ChildPriceAst = ChildPct(frac)

fun childRr(multiplier: ExprAst): ChildPriceAst = ChildRr(multiplier)

fun bracket(
    stopLoss: ChildPriceAst,
    takeProfit: ChildPriceAst,
): BracketAst = BracketAst(stopLoss, takeProfit)

fun oco(
    stop: ChildPriceAst,
    limit: ChildPriceAst,
): OcoAst = OcoAst(stop, limit)
