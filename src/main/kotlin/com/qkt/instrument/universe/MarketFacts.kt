package com.qkt.instrument.universe

import java.math.BigDecimal

/**
 * The as-of market figures a symbol-set filter may read ([CatalogField.market]): for one instrument, the figures of
 * the last window of the set's `EVERY` that closed at or before the evaluation instant. A figure nobody recorded
 * (the instrument printed nothing yet) is null, so it fails comparisons and ranks last.
 */
fun interface MarketFacts {
    /** [field] of [qktSymbol] (`VENUE:SYMBOL`), or null when unknown. */
    fun read(
        field: CatalogField,
        qktSymbol: String,
    ): BigDecimal?

    companion object {
        /** No market figures at all: every market field reads null. */
        val NONE: MarketFacts = MarketFacts { _, _ -> null }
    }
}
