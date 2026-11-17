package com.qkt.instrument.universe

import com.qkt.instrument.InstrumentMeta

/**
 * The members of a filtered symbol set, resolved once from a venue's catalog: every instrument of the venue that
 * passes the filter, ranked by one field, the top [n]. Ties, and instruments missing the ranking field (which sort
 * last), are broken by symbol, so the same catalog always yields the same members in the same order.
 */
object UniverseResolver {
    /** The most members a filtered set may ask for (`TOP n`) until a config cap lands (design §7.1). */
    const val DEFAULT_MAX_MEMBERS: Int = 500

    /**
     * The members of `VENUE:* WHERE [filter] TOP [n] BY [by]` among [catalog], as of [nowMs], market fields read from
     * [facts]; [venue] is the qkt broker prefix and is matched ignoring case.
     */
    fun members(
        catalog: List<InstrumentMeta>,
        venue: String,
        filter: UniverseFilter,
        n: Int,
        by: CatalogField,
        descending: Boolean,
        nowMs: Long,
        facts: MarketFacts = MarketFacts.NONE,
    ): List<InstrumentMeta> {
        require(n > 0) { "TOP must be a positive count: $n" }
        val prefix = "$venue:"
        val passing =
            catalog
                .filter { it.qktSymbol.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) }
                .filter { filter.matches(it, nowMs, facts) }
        val byValue =
            object : Comparator<InstrumentMeta> {
                override fun compare(
                    a: InstrumentMeta,
                    b: InstrumentMeta,
                ): Int {
                    val va = by.read(a, nowMs, facts)
                    val vb = by.read(b, nowMs, facts)
                    return when {
                        va == null && vb == null -> 0
                        va == null -> 1
                        vb == null -> -1
                        descending -> CatalogValue.ordering.compare(vb, va)
                        else -> CatalogValue.ordering.compare(va, vb)
                    }
                }
            }
        return passing.sortedWith(byValue.thenComparing(InstrumentMeta::qktSymbol)).take(n)
    }
}
