package com.qkt.indicators

class IndicatorMap<T : Indicator<*>>(
    private val factory: () -> T,
) {
    private val map: MutableMap<String, T> = mutableMapOf()

    fun get(symbol: String): T = map.getOrPut(symbol) { factory() }

    fun has(symbol: String): Boolean = symbol in map

    fun symbols(): Set<String> = map.keys

    fun all(): Map<String, T> = map.toMap()
}
