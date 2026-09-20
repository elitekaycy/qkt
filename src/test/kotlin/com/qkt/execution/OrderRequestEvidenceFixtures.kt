package com.qkt.execution

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildPriceAst
import java.math.BigDecimal

object OrderRequestEvidenceFixtures {
    const val SYMBOL = "XAUUSD"
    const val STRATEGY = "evidence"
    const val TS = 1_718_000_000_000L
    val QTY: BigDecimal = BigDecimal("0.10")

    fun bracketRequest(
        id: String,
        stopLoss: StopLossSpec,
        takeProfitAst: ChildPriceAst?,
        stopLossAst: ChildPriceAst?,
    ): OrderRequest.Bracket =
        OrderRequest.Bracket(
            id = id,
            symbol = SYMBOL,
            side = Side.BUY,
            quantity = QTY,
            entry = market("$id-entry"),
            takeProfit = BigDecimal("108.00"),
            stopLoss = stopLoss,
            timeInForce = TimeInForce.GTC,
            timestamp = TS,
            strategyId = STRATEGY,
            takeProfitAst = takeProfitAst,
            stopLossAst = stopLossAst,
        )

    fun market(id: String): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = SYMBOL,
            side = Side.BUY,
            quantity = QTY,
            timeInForce = TimeInForce.GTC,
            timestamp = TS,
            strategyId = STRATEGY,
        )

    fun permittedNames(type: Class<*>): Set<String> = type.permittedSubclasses.map { it.simpleName }.toSet()
}
