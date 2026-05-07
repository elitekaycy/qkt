package com.qkt.dsl.ast

data class DefaultsBlock(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val stopLoss: ChildPriceAst? = null,
    val takeProfit: ChildPriceAst? = null,
    val trailing: OrderTypeAst? = null,
)
