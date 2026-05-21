package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.StreamFieldRef

data class StreamRef(
    val alias: String,
) {
    val close: ExprAst = StreamFieldRef(alias, "close")
    val open: ExprAst = StreamFieldRef(alias, "open")
    val high: ExprAst = StreamFieldRef(alias, "high")
    val low: ExprAst = StreamFieldRef(alias, "low")
    val volume: ExprAst = StreamFieldRef(alias, "volume")
    val price: ExprAst = StreamFieldRef(alias, "price")
    val candle: ExprAst = StreamFieldRef(alias, "candle")
    val bid: ExprAst = StreamFieldRef(alias, "bid")
    val ask: ExprAst = StreamFieldRef(alias, "ask")
    val spread: ExprAst = StreamFieldRef(alias, "spread")
}
