package com.qkt.observe.insights

import java.math.BigDecimal

/**
 * Insights translation for equity samples, per strategy and aggregated per portfolio book.
 * These are the only feed for the collector's equity and drawdown panels. Mixed into
 * [InsightsTranslate].
 */
interface EquityInsights {
    /**
     * Per-strategy equity sample ("snapshot.equity"): the store's `equity_snapshots` rows and
     * `strategies.equity/starting_balance` are fed ONLY by this type, so its absence blanks
     * every equity/drawdown panel even while venue `state.*` streams fine (#1073). Emitted on
     * the STATE poller cadence from the session's [com.qkt.pnl.StrategyPnL] view.
     */
    fun equitySnapshot(
        ts: Long,
        strategyId: String,
        realized: BigDecimal,
        unrealized: BigDecimal,
        equity: BigDecimal,
        startingBalance: BigDecimal,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "eq-$strategyId-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId,
            type = "snapshot.equity",
            payload =
                mapOf(
                    "strategyId" to strategyId,
                    "realized" to realized,
                    "unrealized" to unrealized,
                    "equity" to equity,
                    "startingBalance" to startingBalance,
                ),
        )

    /** Records an aggregated realized/unrealized equity sample for a portfolio book. */
    fun portfolioEquityUpdated(
        portfolioId: String,
        ts: Long,
        equity: BigDecimal,
        realized: BigDecimal,
        unrealized: BigDecimal,
        perStrategy: Map<String, BigDecimal>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "portfolio-equity-$portfolioId-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "portfolio.equity.updated",
            payload =
                mapOf(
                    "portfolioId" to portfolioId,
                    "equity" to equity,
                    "realized" to realized,
                    "unrealized" to unrealized,
                    "perStrategy" to perStrategy,
                    "ts" to ts,
                ),
        )
}
