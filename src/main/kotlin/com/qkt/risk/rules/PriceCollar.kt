package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Price collar (MiFID II RTS 6): an order's explicit price must lie within
 * [maxDeviationFrac] of the last seen market price. Catches orders priced off stale
 * or corrupted references — the fat-finger / frozen-feed class — before the venue
 * sees them. Market orders carry no price and pass; symbols with no market price yet
 * pass (the notional cap separately rejects unpriceable orders).
 */
class PriceCollar(
    private val maxDeviationFrac: BigDecimal,
    private val prices: MarketPriceProvider,
) : RiskRule {
    init {
        require(maxDeviationFrac.signum() > 0) { "maxDeviationFrac must be > 0: $maxDeviationFrac" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val last = prices.lastPrice(request.symbol) ?: return Decision.Approve
        if (last.signum() <= 0) return Decision.Approve
        for (p in explicitPrices(request)) {
            val deviation = p.subtract(last).abs().divide(last, Money.CONTEXT)
            if (deviation > maxDeviationFrac) {
                return Decision.Reject(
                    "order price ${p.toPlainString()} deviates ${deviation.toPlainString()} " +
                        "from last ${last.toPlainString()} (collar ${maxDeviationFrac.toPlainString()})",
                )
            }
        }
        return Decision.Approve
    }

    private fun explicitPrices(request: OrderRequest): List<BigDecimal> =
        when (request) {
            is OrderRequest.Limit -> listOf(request.limitPrice)
            is OrderRequest.Stop -> listOf(request.stopPrice)
            is OrderRequest.StopLimit -> listOf(request.stopPrice, request.limitPrice)
            is OrderRequest.IfTouched -> listOf(request.triggerPrice)
            is OrderRequest.Bracket -> explicitPrices(request.entry)
            else -> emptyList()
        }
}
