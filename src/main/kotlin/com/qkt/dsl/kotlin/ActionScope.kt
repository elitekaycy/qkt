package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.TifAst

object ActionScope {
    fun buy(
        stream: StreamRef,
        qty: ExprAst,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Buy(
            stream.alias,
            ActionOpts(
                sizing = SizeQty(qty),
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )

    fun buy(
        stream: StreamRef,
        sizing: SizingAst,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Buy(
            stream.alias,
            ActionOpts(
                sizing = sizing,
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )

    fun sell(
        stream: StreamRef,
        qty: ExprAst,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Sell(
            stream.alias,
            ActionOpts(
                sizing = SizeQty(qty),
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )

    fun sell(
        stream: StreamRef,
        sizing: SizingAst,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Sell(
            stream.alias,
            ActionOpts(
                sizing = sizing,
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )

    fun log(message: String): ActionAst = Log(message)

    fun closeStream(stream: StreamRef): ActionAst = Close(stream.alias)

    fun closeAll(): ActionAst = CloseAll

    fun cancelStream(stream: StreamRef): ActionAst =
        com.qkt.dsl.ast
            .Cancel(stream.alias)

    fun cancelAll(): ActionAst = com.qkt.dsl.ast.CancelAll

    fun buy(
        stream: StreamRef,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Buy(
            stream.alias,
            ActionOpts(
                sizing = null,
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )

    fun sell(
        stream: StreamRef,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Sell(
            stream.alias,
            ActionOpts(
                sizing = null,
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )
}
