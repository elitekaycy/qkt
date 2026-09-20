package com.qkt.observe.insights

import java.math.BigDecimal

/**
 * Insights translation for portfolio books: the book's configuration and its child
 * allocation weights. Mixed into [InsightsTranslate].
 */
interface PortfolioInsights {
    /** Announces a deployed portfolio book so Insights can retain book-level metadata. */
    fun portfolioConfigured(
        portfolioId: String,
        ts: Long,
        capital: BigDecimal?,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "portfolio-configured-$portfolioId-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "portfolio.configured",
            payload = mapOf("portfolioId" to portfolioId, "capital" to capital, "ts" to ts),
        )

    /** Records the current child allocation weights for a portfolio book. */
    fun portfolioAllocationUpdated(
        portfolioId: String,
        ts: Long,
        allocations: Map<String, BigDecimal>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "portfolio-allocation-$portfolioId-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "portfolio.allocation.updated",
            payload = mapOf("portfolioId" to portfolioId, "allocations" to allocations, "ts" to ts),
        )
}
