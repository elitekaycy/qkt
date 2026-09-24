package com.qkt.observe.insights

/**
 * Insights translation for market-data source health: connect, disconnect, reconnect and
 * per-symbol unhealthy/recovered transitions. Mixed into [InsightsTranslate].
 */
interface MarketDataInsights {
    fun marketDataConnected(
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String = "session-start",
    ): InsightsEnvelope = marketDataLifecycle("connected", "marketdata.connected", source, symbols, ts, reason)

    fun marketDataDisconnected(
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String,
    ): InsightsEnvelope = marketDataLifecycle("disconnected", "marketdata.disconnected", source, symbols, ts, reason)

    fun marketDataReconnected(
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String = "source-reconnected",
    ): InsightsEnvelope = marketDataLifecycle("reconnected", "marketdata.reconnected", source, symbols, ts, reason)

    /**
     * Per-symbol quote-health transition detected while the source itself remains connected.
     * [kind] names the fault — `stale` (quote age), `clock_skew` or `outlier` — and is omitted
     * from the payload when null.
     */
    fun marketDataStale(
        source: String,
        symbol: String,
        ts: Long,
        reason: String,
        kind: String? = null,
    ): InsightsEnvelope =
        marketDataLifecycle(
            "stale",
            "marketdata.stale",
            source,
            listOf(symbol),
            ts,
            reason,
            if (kind == null) emptyMap() else mapOf("kind" to kind),
        )

    /**
     * The end of a `marketdata.stale` episode for [symbol]: the symbol is healthy again after
     * [unhealthyForMs] milliseconds measured from the episode's first stale event.
     */
    fun marketDataRecovered(
        source: String,
        symbol: String,
        ts: Long,
        reason: String,
        unhealthyForMs: Long,
    ): InsightsEnvelope =
        marketDataLifecycle(
            "recovered",
            "marketdata.recovered",
            source,
            listOf(symbol),
            ts,
            reason,
            mapOf("unhealthyForMs" to unhealthyForMs),
        )
}

private fun marketDataLifecycle(
    state: String,
    type: String,
    source: String,
    symbols: List<String>,
    ts: Long,
    reason: String,
    extra: Map<String, Any?> = emptyMap(),
): InsightsEnvelope =
    InsightsEnvelope(
        id = "marketdata-$state-$source-$ts",
        seq = 0,
        ts = ts,
        strategyId = null,
        type = type,
        payload =
            mapOf(
                "source" to source,
                "symbols" to symbols,
                "state" to state,
                "reason" to reason,
                "ts" to ts,
            ) + extra,
    )
