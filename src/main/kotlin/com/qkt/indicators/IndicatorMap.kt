package com.qkt.indicators

/**
 * Per-symbol indicator container — one independent indicator instance per symbol,
 * lazily constructed via [factory] on first access. Used by multi-symbol strategies
 * that want one `EMA(9)` of XAUUSD and one of XAGUSD running side by side.
 */
class IndicatorMap<T : Indicator<*>>(
    private val factory: () -> T,
) {
    private val map: MutableMap<String, T> = mutableMapOf()

    /** Get or create the indicator for [symbol]. Construction is one-shot per symbol. */
    fun get(symbol: String): T = map.getOrPut(symbol) { factory() }

    /** True if an indicator has already been created for [symbol]. */
    fun has(symbol: String): Boolean = symbol in map

    /** Snapshot of currently-tracked symbols. */
    fun symbols(): Set<String> = map.keys

    /** Snapshot of every symbol → indicator pair. */
    fun all(): Map<String, T> = map.toMap()
}
