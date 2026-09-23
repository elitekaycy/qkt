package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Build the stack signal: a [OrderRequest.Bracket] with id [stackLegId] on the parent's
 * [symbol] and [side], sized to [ResolvedStackTier.stackQuantity] and stamped at [ts].
 *
 * SL and TP start at [fillAnchor] ± the tier's distances, where [fillAnchor] is the price the
 * market leg is expected to fill at (ask for a BUY, bid for a SELL) — the level a venue
 * validates the submitted protection against. The distances also ride along as `BY`
 * expressions, so the bracket re-anchors on the leg's actual fill exactly as a primary does.
 */
internal fun stackBracketSignal(
    stackLegId: String,
    symbol: String,
    side: Side,
    tier: ResolvedStackTier,
    fillAnchor: BigDecimal,
    ts: Long,
): Signal {
    val (sl, tp) =
        when (side) {
            Side.BUY -> fillAnchor.subtract(tier.slDistance) to fillAnchor.add(tier.tpDistance)
            Side.SELL -> fillAnchor.add(tier.slDistance) to fillAnchor.subtract(tier.tpDistance)
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
                takeProfitAst = ChildBy(NumLit(tier.tpDistance)),
                stopLossAst = ChildBy(NumLit(tier.slDistance)),
            ),
        )
    return signal
}
