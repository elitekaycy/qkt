package com.qkt.connector.mt5

import com.qkt.common.SymbolCalendars

/**
 * Everything [com.qkt.connector.mt5.marketdata.Mt5MarketSource] actually reads from an
 * [MT5BrokerProfile]. Profiles sharing this identity poll the same gateway with the same
 * credentials, symbol translation, poll cadence, session calendars, and history retries — one live
 * poller can serve all of them. `name` and `magic` are deliberately absent: they tag orders, not
 * market data.
 */
internal data class Mt5MarketDataIdentity(
    val gatewayUrl: String,
    val apiKey: String?,
    val serverTimeZone: MT5ServerTimeZone,
    val symbolPolicy: SymbolPolicy,
    val tickPollIntervalMs: Long,
    val symbolCalendars: SymbolCalendars,
    val retryAttempts: Int,
) {
    companion object {
        fun of(profile: MT5BrokerProfile): Mt5MarketDataIdentity =
            Mt5MarketDataIdentity(
                gatewayUrl = profile.gatewayUrl,
                apiKey = profile.apiKey,
                serverTimeZone = profile.serverTimeZone,
                symbolPolicy = profile.symbolPolicy,
                tickPollIntervalMs = profile.tickPollIntervalMs,
                symbolCalendars = profile.symbolCalendars,
                retryAttempts = profile.retryAttempts,
            )
    }
}

/**
 * Group profiles by [Mt5MarketDataIdentity], preserving declaration order: the first profile of a
 * group is its canonical poller owner.
 */
internal fun groupByMarketDataIdentity(profiles: List<MT5BrokerProfile>): List<List<MT5BrokerProfile>> =
    profiles.groupBy(Mt5MarketDataIdentity::of).values.toList()
