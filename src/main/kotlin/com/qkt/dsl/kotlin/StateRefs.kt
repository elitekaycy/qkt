package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource

object Account {
    val realizedPnl: ExprAst = AccountRef("realized_pnl")
    val unrealizedPnl: ExprAst = AccountRef("unrealized_pnl")
    val totalPnl: ExprAst = AccountRef("total_pnl")
}

fun position(stream: StreamRef): ExprAst = PositionRef(stream.alias)

fun positionAvgPrice(stream: StreamRef): ExprAst = StateAccessor(StateSource.POSITION_AVG_PRICE, stream.alias)
