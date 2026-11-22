package com.qkt.marketdata.source

import com.qkt.marketdata.TickFeed
import org.slf4j.LoggerFactory

/**
 * Opens one feed for a batch of symbols, and when the venue refuses the batch finds the symbols it refuses, so one
 * unservable symbol (a contract that expired, a market the venue will not quote) never blocks the others.
 *
 * e.g. kept `[B]`, fresh `[BAD, C]`: the venue refuses `[B, BAD, C]`, so `BAD` and `C` are tried alone, `BAD` is
 * left out and one feed of `[B, C]` is opened.
 *
 * A symbol is tried alone only after a batch was refused: the feed of a single symbol is opened and closed again.
 */
internal class DegradingOpen(
    private val open: (List<String>) -> TickFeed,
) {
    private val log = LoggerFactory.getLogger(DegradingOpen::class.java)

    /**
     * One feed of [kept] (symbols served until now) and [fresh] (symbols asked for now), without those the venue
     * refuses, and the symbols it serves; null when no fresh symbol can be served, so the running feed stays.
     */
    fun open(
        kept: List<String>,
        fresh: List<String>,
    ): Pair<TickFeed, List<String>>? {
        attempt(kept + fresh)?.let { return it to kept + fresh }
        val servable = fresh.filter(::serves)
        if (servable.size < fresh.size) {
            log.warn("cannot subscribe {}; the venue refuses them", fresh - servable.toSet())
            if (servable.isEmpty()) return null
            attempt(kept + servable)?.let { return it to kept + servable }
        }
        val live = kept.filter(::serves)
        log.warn("the venue no longer serves {}; they are left out of the feed", kept - live.toSet())
        return attempt(live + servable)?.let { it to live + servable }
    }

    private fun attempt(symbols: List<String>): TickFeed? = runCatching { open(symbols) }.getOrNull()

    private fun serves(symbol: String): Boolean = runCatching { open(listOf(symbol)).close() }.isSuccess
}
