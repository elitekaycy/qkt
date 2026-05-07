package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty

object ActionScope {
    fun buy(
        stream: StreamRef,
        qty: ExprAst,
    ): ActionAst = Buy(stream.alias, ActionOpts(sizing = SizeQty(qty), orderType = Market))

    fun sell(
        stream: StreamRef,
        qty: ExprAst,
    ): ActionAst = Sell(stream.alias, ActionOpts(sizing = SizeQty(qty), orderType = Market))
}
