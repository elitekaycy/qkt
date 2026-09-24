package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.SymbolSessionProvider
import com.qkt.common.Clock
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketDataGateConfig

/**
 * The live session's stale/outlier judgment over its feeds (#395): [config]'s thresholds,
 * session and scheduled-break answers from [venues] (read at call time, so venues built after
 * the gate count), and every unhealthy/recovered transition routed to [alerts].
 */
internal fun liveMarketDataGate(
    clock: Clock,
    config: MarketDataGateConfig,
    venues: () -> List<Broker>,
    alerts: MarketDataHealthAlerts,
): MarketDataGate =
    MarketDataGate(
        clock = clock,
        staleAgeMultiple = config.staleAgeMultiple,
        minStaleAgeMs = config.minStaleAgeMs,
        outlierSigma = config.outlierSigma,
        maxClockSkewMs = config.maxClockSkewMs,
        inSession = { symbol, nowMs -> symbolInSession(venues(), symbol, nowMs) },
        scheduledBreak = { symbol, nowMs -> venues().any { it.scheduledBreak(symbol, nowMs) } },
        onUnhealthy = alerts::onUnhealthy,
        onRecovered = alerts::onRecovered,
    )

/**
 * Whether [symbol] trades at [nowMs]: under its own calendar when a venue can say
 * ([SymbolSessionProvider]), so gold sleeps at the weekend while BTC on the same account
 * trades; otherwise whether any venue is open ([Broker.marketOpen]).
 */
internal fun symbolInSession(
    venues: List<Broker>,
    symbol: String,
    nowMs: Long,
): Boolean {
    val perSymbol = venues.filterIsInstance<SymbolSessionProvider>()
    if (perSymbol.isEmpty()) return venues.any { it.marketOpen(nowMs) }
    return perSymbol.any { it.symbolInSession(symbol, nowMs) }
}
