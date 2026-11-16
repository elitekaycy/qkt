package com.qkt.dsl.sets

import com.qkt.instrument.universe.VenueCatalog

/**
 * Where a run's filtered symbol sets read their instruments: the instruments file in a backtest, the venue's listing
 * live. Both feed the same resolver, so the same instruments always give the same members.
 */
interface SetCatalog {
    /** How a refusal names this catalog: `the instruments file`, `the venue listing`. */
    val source: String

    /** True when the catalog has no listing history, so a set over equities would see only today's survivors. */
    val survivorsOnly: Boolean

    /** The instruments of [spec]'s venue, read for the set [alias]; a venue the catalog cannot read is refused. */
    fun venue(
        alias: String,
        spec: SymbolSetSpec.Filtered,
    ): VenueCatalog
}
