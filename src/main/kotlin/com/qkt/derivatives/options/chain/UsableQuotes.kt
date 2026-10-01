package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionListing

/**
 * The quotes of [snapshot] a model may trust: catalogued in [listings], unexpired at the snapshot,
 * with a positive mark IV no older than [maxQuoteAgeMs].
 */
internal fun usableQuotes(
    snapshot: ChainSnapshot,
    listings: Map<String, OptionListing>,
    maxQuoteAgeMs: Long,
): List<ChainQuote> =
    snapshot.quotes.filter { q ->
        val listing = listings[q.contract]
        listing != null &&
            listing.expiryMs > snapshot.atMs &&
            (q.markIv?.signum() ?: 0) > 0 &&
            q.markAgeMs <= maxQuoteAgeMs
    }

/** One expiry's forward: the median `underlying` of its usable [quotes] (not empty). */
internal fun medianForward(quotes: List<ChainQuote>): Double {
    val sorted = quotes.map { it.underlying.toDouble() }.sorted()
    return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
}
