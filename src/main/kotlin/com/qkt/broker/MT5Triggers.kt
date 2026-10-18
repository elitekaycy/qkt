package com.qkt.broker

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.marketdata.buyExecPrice
import com.qkt.marketdata.sellExecPrice

/**
 * Whether [tick] triggers [req] in [MT5BrokerSimulator]. Side-aware like MT5 itself: BUY_STOP fires on
 * the ask, SELL_STOP on the bid. See [buyExecPrice].
 */
internal fun mt5Triggered(
    req: OrderRequest,
    tick: Tick,
): Boolean {
    val exec =
        if (req.side == Side.BUY) tick.buyExecPrice() else tick.sellExecPrice()
    return when (req) {
        is OrderRequest.Limit ->
            if (req.side == Side.BUY) exec <= req.limitPrice else exec >= req.limitPrice
        is OrderRequest.Stop ->
            if (req.side == Side.BUY) exec >= req.stopPrice else exec <= req.stopPrice
        is OrderRequest.StopLimit ->
            if (req.side == Side.BUY) exec >= req.stopPrice else exec <= req.stopPrice
        is OrderRequest.IfTouched ->
            if (req.side == Side.BUY) exec <= req.triggerPrice else exec >= req.triggerPrice
        is OrderRequest.Market -> false
        else -> error("MT5BrokerSimulator.mt5Triggered: unhandled order type ${req::class.simpleName}")
    }
}
