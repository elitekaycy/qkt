package com.qkt.connector.mt5

import java.util.concurrent.ConcurrentHashMap

/**
 * The folder each symbol is filed under at an MT5 venue (`Stocks` for `Standard\Stocks\AAPLm`), read from
 * `/symbol_info` once per symbol, for `category:` calendar rules. A gateway that cannot answer is not remembered as
 * "no category": the symbol is asked again, but not before [retryAfterNanos], so a poller reading calendars during an
 * outage never waits on the gateway each round. A strategy's deploy reads the same endpoint for its instruments, so
 * the cache is warm before a poller asks.
 */
internal class MT5SymbolCategories(
    private val info: (String) -> MT5SymbolInfo?,
    private val symbols: MT5Symbol,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val retryAfterNanos: Long = 60_000_000_000L,
) : (String) -> String? {
    private val known = ConcurrentHashMap<String, String>()
    private val none = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override fun invoke(bareSymbol: String): String? {
        known[bareSymbol]?.let { return it }
        if (bareSymbol in none) return null
        failedAt[bareSymbol]?.let { if (monotonicNanos() - it < retryAfterNanos) return null }
        // A body the gateway should not have sent is a failed read too: a calendar question must never throw.
        val reported = runCatching { info(symbols.toBroker(bareSymbol)) }.getOrNull()
        if (reported == null) {
            failedAt[bareSymbol] = monotonicNanos()
            return null
        }
        failedAt.remove(bareSymbol)
        val category = reported.category
        if (category == null) none += bareSymbol else known[bareSymbol] = category
        return category
    }
}

/** [profile] with its calendars reading each symbol's category through [client], as an opened account trades on. */
internal fun MT5BrokerProfile.withVenueCategories(client: MT5Client): MT5BrokerProfile =
    copy(
        symbolCalendars =
            symbolCalendars.withCategories(
                MT5SymbolCategories(client::getSymbolInfo, MT5Symbol(symbolPolicy)),
            ),
    )

/** [profile] with its calendars reading categories through a read-only client of its own gateway. */
internal fun MT5BrokerProfile.withVenueCategories(): MT5BrokerProfile =
    withVenueCategories(MT5Client(gatewayUrl, serverTimeZone, httpTimeoutMs, retryAttempts, apiKey))
