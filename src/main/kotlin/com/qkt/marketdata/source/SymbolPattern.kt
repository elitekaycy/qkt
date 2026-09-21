package com.qkt.marketdata.source

/** Which symbols a route serves, e.g. `prefix("EXNESS:")` for one venue or `exactSet(...)` for a pinned list. */
fun interface SymbolPattern {
    fun matches(symbol: String): Boolean

    companion object {
        fun prefix(prefix: String): SymbolPattern = SymbolPattern { it.startsWith(prefix) }

        fun exact(symbol: String): SymbolPattern = SymbolPattern { it == symbol }

        fun exactSet(symbols: Set<String>): SymbolPattern = SymbolPattern { it in symbols }
    }
}
