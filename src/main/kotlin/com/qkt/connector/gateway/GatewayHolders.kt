package com.qkt.connector.gateway

import java.math.BigDecimal

/**
 * The account-level check of a gateway account shared by several strategies: once every strategy
 * expected on the account has a restored, ready session, what their sessions hold must add up, symbol
 * by symbol, to what the account holds at the venue. Per-strategy holdings cannot be checked against a
 * netting venue, which only knows the total.
 */
internal class GatewayHolders(
    private val symbols: GatewaySymbols,
) {
    private val ready = HashSet<GatewayRouting.Attached>()

    /** [broker]'s session has restored its positions. */
    fun ready(broker: GatewayRouting.Attached) {
        ready += broker
    }

    /**
     * Why the ready sessions among [attached] disagree with [account] (net by venue code), or null when
     * they agree or not every strategy of [expected] is ready yet. A broker serving every strategy (null)
     * covers them all.
     */
    fun mismatch(
        attached: List<GatewayRouting.Attached>,
        expected: Set<String>,
        account: Map<String, BigDecimal>,
    ): String? {
        val present = attached.filter { it in ready }
        val covered = present.any { it.strategy == null } || present.mapNotNull { it.strategy }.containsAll(expected)
        if (!covered || present.isEmpty()) return null
        val held = HashMap<String, BigDecimal>()
        for (broker in present) {
            for (symbol in broker.positions.symbols()) {
                val code = symbols.venue(symbol) ?: continue
                held[code] = (held[code] ?: BigDecimal.ZERO).add(broker.holding(symbol))
            }
        }
        val differ =
            (held.keys + account.keys).filter { code ->
                (held[code] ?: BigDecimal.ZERO).compareTo(account[code] ?: BigDecimal.ZERO) != 0
            }
        if (differ.isEmpty()) return null
        return differ.joinToString("; ", prefix = "strategies hold, account holds: ") { code ->
            "$code ${(held[code] ?: BigDecimal.ZERO).toPlainString()} vs ${(account[code] ?: BigDecimal.ZERO).toPlainString()}"
        }
    }
}
