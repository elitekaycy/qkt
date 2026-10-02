package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionListing

/**
 * The quotes of [snapshot] a model may trust at [nowMs] (at or after the snapshot): catalogued in
 * [listings], unexpired at [nowMs], with a positive mark IV whose age at [nowMs] (its age in the
 * snapshot plus the time since) is within [maxQuoteAgeMs].
 */
internal fun usableQuotes(
    snapshot: ChainSnapshot,
    listings: Map<String, OptionListing>,
    maxQuoteAgeMs: Long,
    nowMs: Long,
): List<ChainQuote> {
    require(nowMs >= snapshot.atMs) { "a snapshot at ${snapshot.atMs} is read at $nowMs, before it was taken" }
    val since = nowMs - snapshot.atMs
    return snapshot.quotes.filter { q ->
        val listing = listings[q.contract]
        listing != null &&
            listing.expiryMs > nowMs &&
            (q.markIv?.signum() ?: 0) > 0 &&
            q.markAgeMs + since <= maxQuoteAgeMs
    }
}

/** One expiry's forward: the median `underlying` of its usable [quotes] (not empty). */
internal fun medianForward(quotes: List<ChainQuote>): Double {
    val sorted = quotes.map { it.underlying.toDouble() }.sorted()
    return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
}
