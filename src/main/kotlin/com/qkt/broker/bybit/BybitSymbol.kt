package com.qkt.broker.bybit

/** Maps qkt symbols (`BYBIT_SPOT:BTCUSDT`) to Bybit's `(category, symbol)` REST parameters. */
object BybitSymbol {
    private val CATEGORY_BY_PREFIX: Map<String, String> =
        mapOf(
            "BYBIT_SPOT:" to "spot",
            "BYBIT_LINEAR:" to "linear",
            "BYBIT_INVERSE:" to "inverse",
            "BYBIT_OPTION:" to "option",
        )

    private val PREFIX_BY_CATEGORY: Map<String, String> =
        CATEGORY_BY_PREFIX.entries.associate { (k, v) -> v to k }

    data class Parsed(
        val category: String,
        val bare: String,
    )

    fun parse(qktSymbol: String): Parsed {
        val entry =
            CATEGORY_BY_PREFIX.entries.firstOrNull { qktSymbol.startsWith(it.key) }
                ?: throw IllegalArgumentException("Not a recognized Bybit symbol: $qktSymbol")
        val bare = qktSymbol.removePrefix(entry.key)
        return Parsed(category = entry.value, bare = bare)
    }

    fun toQkt(
        category: String,
        bare: String,
    ): String {
        val prefix = PREFIX_BY_CATEGORY[category] ?: error("Unknown Bybit category: $category")
        return "$prefix$bare"
    }
}
