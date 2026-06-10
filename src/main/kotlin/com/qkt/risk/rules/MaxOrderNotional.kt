package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Per-order notional (currency value) cap — qty x reference price x contractSize must
 * not exceed [maxNotional]. The reference is the order's own price field when it has
 * one, else the last seen market price. An order whose notional CANNOT be computed
 * (no price reference at all) is rejected: a cap that silently passes unpriceable
 * orders is not a cap (FIA §1.1, SEC 15c3-5).
 */
class MaxOrderNotional(
    private val maxNotional: BigDecimal,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
) : RiskRule {
    init {
        require(maxNotional.signum() > 0) { "maxNotional must be > 0: $maxNotional" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val reference =
            explicitPrice(request) ?: prices.lastPrice(request.symbol)
                ?: return Decision.Reject(
                    "cannot compute notional for ${request.symbol}: no price reference",
                )
        val cs = instruments.lookup(request.symbol)?.contractSize ?: BigDecimal.ONE
        val notional = request.quantity.multiply(reference).multiply(cs)
        return if (notional > maxNotional) {
            Decision.Reject(
                "order notional ${notional.toPlainString()} exceeds cap ${maxNotional.toPlainString()} " +
                    "(qty=${request.quantity.toPlainString()} ref=${reference.toPlainString()} contractSize=$cs)",
            )
        } else {
            Decision.Approve
        }
    }

    private fun explicitPrice(request: OrderRequest): BigDecimal? =
        when (request) {
            is OrderRequest.Limit -> request.limitPrice
            is OrderRequest.Stop -> request.stopPrice
            is OrderRequest.StopLimit -> request.stopPrice
            is OrderRequest.IfTouched -> request.triggerPrice
            is OrderRequest.Bracket -> explicitPrice(request.entry)
            else -> null
        }
}
