package com.qkt.marketdata

/**
 * Pre-trade rule companion to [MarketDataGate]: rejects NEW-exposure orders for a
 * symbol whose data is unhealthy (stale quotes or a skewed broker clock);
 * risk-REDUCING orders (opposite side, no larger than the open position, or
 * close-by-ticket) still pass — bad data is a reason to stop adding risk, not a
 * reason to trap the position.
 */
class MarketDataHealthRule(
    private val gate: MarketDataGate,
) : com.qkt.risk.RiskRule {
    override fun evaluate(
        request: com.qkt.execution.OrderRequest,
        positions: com.qkt.positions.PositionProvider,
    ): com.qkt.risk.Decision {
        if (gate.isHealthy(request.symbol)) return com.qkt.risk.Decision.Approve
        if (com.qkt.risk.isRiskReducing(request, positions)) return com.qkt.risk.Decision.Approve
        return com.qkt.risk.Decision.Reject(
            "market data for ${request.symbol} is unhealthy (stale or clock-skewed) — " +
                "new orders suppressed until data recovers",
        )
    }
}
