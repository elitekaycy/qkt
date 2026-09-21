package com.qkt.app

import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Strategy

/**
 * What a live session does when the market-data gate judges a symbol unhealthy: alert every
 * hosted strategy (when error alerts are on) and tell insights, e.g. "market data unhealthy for
 * EXNESS:XAUUSD: stale 45000ms; 2 engine-held protective stop(s) cannot trigger without ticks".
 */
internal class MarketDataHealthAlerts(
    private val strategies: List<Pair<String, Strategy>>,
    private val sessionNotifier: SessionNotifier,
    private val insights: InsightsLifecycle,
) {
    /** Count of protective stops only the engine holds; bound once the pipeline exists. */
    var engineHeldProtectiveStopCount: () -> Int = { 0 }

    /** The gate's unhealthy callback for [symbol]. */
    fun onUnhealthy(
        symbol: String,
        reason: String,
    ) {
        if (sessionNotifier.enabled(NotifyEventKind.STRATEGY_ERROR)) {
            for ((strategyId, _) in strategies) {
                sessionNotifier.strategyError(strategyId, "MarketDataUnhealthy") {
                    "market data unhealthy for $symbol: $reason; " +
                        "${engineHeldProtectiveStopCount()} engine-held protective stop(s) " +
                        "cannot trigger without ticks"
                }
            }
        }
        insights.marketDataStale(symbol, reason)
    }
}
