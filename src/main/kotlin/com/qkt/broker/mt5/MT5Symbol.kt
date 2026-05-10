package com.qkt.broker.mt5

/** Bidirectional translator between qkt-side symbols and broker-side MT5 symbols per a [SymbolPolicy]. */
class MT5Symbol(
    private val policy: SymbolPolicy,
) {
    private val reverseAliases: Map<String, String> =
        policy.aliases.entries.associate { (k, v) -> v to k }

    fun toBroker(qktSymbol: String): String {
        val base = policy.aliases[qktSymbol] ?: qktSymbol
        return base + policy.suffix
    }

    fun toQkt(brokerSymbol: String): String {
        val withoutSuffix =
            if (policy.suffix.isNotEmpty() && brokerSymbol.endsWith(policy.suffix)) {
                brokerSymbol.removeSuffix(policy.suffix)
            } else {
                brokerSymbol
            }
        return reverseAliases[withoutSuffix] ?: withoutSuffix
    }
}
