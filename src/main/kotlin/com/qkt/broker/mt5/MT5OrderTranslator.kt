package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest

/** Converts qkt [OrderRequest]s into the JSON wire shape understood by `mt5-gateway`. */
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
) {
    fun translate(req: OrderRequest): MT5OrderRequest =
        when (req) {
            is OrderRequest.Market -> translateMarket(req)
            is OrderRequest.Bracket -> translateBracket(req)
            else ->
                error(
                    "MT5 v1 does not natively translate ${req::class.simpleName}; " +
                        "OrderManager should use engine-managed fallback",
                )
        }

    private fun translateMarket(req: OrderRequest.Market): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateBracket(req: OrderRequest.Bracket): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = req.stopLoss,
            tp = req.takeProfit,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )
}
