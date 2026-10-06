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
    return snapshot.quotes.filter { q ->
        val listing = listings[q.contract]
        listing != null && q.usableAt(nowMs, listing.expiryMs, maxQuoteAgeMs)
    }
}

/**
 * Whether a model may trust this quote of a contract expiring at [expiryMs] at [nowMs]: unexpired, with a
 * positive mark IV whose age at [nowMs] (its age when quoted plus the time since) is within [maxQuoteAgeMs].
 */
internal fun ChainQuote.usableAt(
    nowMs: Long,
    expiryMs: Long,
    maxQuoteAgeMs: Long,
): Boolean = expiryMs > nowMs && (markIv?.signum() ?: 0) > 0 && markAgeMs + (nowMs - atMs) <= maxQuoteAgeMs

/** One expiry's forward: the median `underlying` of its usable [quotes] (not empty). */
internal fun medianForward(quotes: List<ChainQuote>): Double {
    val sorted = quotes.map { it.underlying.toDouble() }.sorted()
    return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
}
