package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Build the stack signal: a [OrderRequest.Bracket] with id [stackLegId] on the parent's
 * [symbol] and [side], sized to [ResolvedStackTier.stackQuantity], with SL and TP computed
 * from [currentPrice] ± the tier's distances and stamped at [ts].
 */
internal fun stackBracketSignal(
    stackLegId: String,
    symbol: String,
    side: Side,
    tier: ResolvedStackTier,
    currentPrice: BigDecimal,
    ts: Long,
): Signal {
    val (sl, tp) =
        when (side) {
            Side.BUY -> currentPrice.subtract(tier.slDistance) to currentPrice.add(tier.tpDistance)
            Side.SELL -> currentPrice.add(tier.slDistance) to currentPrice.subtract(tier.tpDistance)
        }
    val market =
        OrderRequest.Market(
            id = "$stackLegId-entry",
            symbol = symbol,
            side = side,
            quantity = tier.stackQuantity,
            timeInForce = TimeInForce.GTC,
            timestamp = ts,
        )
    val signal =
        Signal.Submit(
            OrderRequest.Bracket(
                id = stackLegId,
                symbol = symbol,
                side = side,
                quantity = tier.stackQuantity,
                entry = market,
                takeProfit = tp,
                stopLoss = StopLossSpec.Fixed(sl),
                timeInForce = TimeInForce.GTC,
                timestamp = ts,
            ),
        )
    return signal
}
