package com.qkt.instrument

import com.qkt.common.SymbolCalendars
import java.util.concurrent.ConcurrentHashMap

/**
 * Each symbol's venue category ([InstrumentMeta.category], e.g. `Stocks`) for `category:` calendar rules, read through
 * a connector's own instrument contract ([instruments], a qkt symbol to its meta) once per symbol. This is the one
 * place a category comes from: a connector reports it on [InstrumentMeta], it never feeds calendars by another path.
 *
 * A venue that cannot answer is not remembered as "no category": the symbol is asked again, but not before
 * [retryAfterNanos], so a poller reading calendars during an outage never waits on the venue each round. A read that
 * throws counts as one that could not answer. A strategy's deploy reads the same instrument spec, so the cache is warm
 * before a poller asks.
 */
class InstrumentCategories(
    private val prefix: String,
    private val instruments: (String) -> InstrumentMeta?,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val retryAfterNanos: Long = 60_000_000_000L,
) : (String) -> String? {
    private val known = ConcurrentHashMap<String, String>()
    private val none = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The category of bare symbol [bareSymbol] (`AAPL` of `EXNESS:AAPL`), or null when the venue reports none. */
    override fun invoke(bareSymbol: String): String? {
        known[bareSymbol]?.let { return it }
        if (bareSymbol in none) return null
        failedAt[bareSymbol]?.let { if (monotonicNanos() - it < retryAfterNanos) return null }
        val meta = runCatching { instruments("${prefix.uppercase()}:$bareSymbol") }.getOrNull()
        if (meta == null) {
            failedAt[bareSymbol] = monotonicNanos()
            return null
        }
        failedAt.remove(bareSymbol)
        val category = meta.category
        if (category == null) none += bareSymbol else known[bareSymbol] = category
        return category
    }
}

/**
 * These calendars with `category:` rules answered from an account's instrument contract: [prefix] is the account's
 * symbol prefix name (`exness`), [instruments] its spec lookup. Every connector binds its calendars this way.
 */
fun SymbolCalendars.withInstrumentCategories(
    prefix: String,
    instruments: (String) -> InstrumentMeta?,
): SymbolCalendars = withCategories(InstrumentCategories(prefix, instruments))
