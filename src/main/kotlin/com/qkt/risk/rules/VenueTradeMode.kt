package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.TradeMode
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing

/**
 * Refuses an order the venue's [TradeMode] for its symbol does not allow, before it reaches the venue: no opening
 * order on a close-only or disabled symbol (Exness keeps `FBm` close-only and `ATVIm` disabled in its list), none on
 * the side a long-only or short-only symbol forbids. An order that only reduces a position passes unless the symbol is
 * disabled outright. A symbol with no instrument entry, or a fully tradable one, is not this rule's to judge.
 */
class VenueTradeMode(
    private val instruments: InstrumentRegistry,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val mode = instruments.lookup(request.symbol)?.tradeMode ?: return Decision.Approve
        if (mode == TradeMode.FULL) return Decision.Approve
        val allowed =
            if (isRiskReducing(request, positions)) mode.allowsClosing else mode.allowsOpening(request.side == Side.BUY)
        if (allowed) return Decision.Approve
        val what = if (isRiskReducing(request, positions)) "closing" else "opening ${request.side}"
        return Decision.Reject("${request.symbol} is ${mode.name.lowercase()} at the venue: $what refused")
    }
}
