package com.qkt.connector.gateway

import java.math.BigDecimal

/**
 * The account-level check of a gateway account: once every strategy expected on the account has a
 * restored, ready session, what their sessions hold must add up, symbol by symbol, to what the account
 * holds at the venue. Per-strategy holdings cannot be checked against a netting venue, which only knows
 * the total.
 */
internal class GatewayHolders(
    private val symbols: GatewaySymbols,
) {
    private val ready = HashSet<GatewayRouting.Attached>()

    /** [broker]'s session has restored its positions. */
    fun ready(broker: GatewayRouting.Attached) {
        ready += broker
    }

    /** A judgement of the account: [mismatch] says why the strategies disagree with it, null when they agree. */
    data class Verdict(
        val mismatch: String?,
    )

    /**
     * Whether the ready sessions among [attached] agree with [account] (net by venue code), or null when
     * some strategy of [expected] is not ready yet, so nothing can be judged. A broker serving every
     * strategy (null) covers them all.
     */
    fun check(
        attached: List<GatewayRouting.Attached>,
        expected: Set<String>,
        account: Map<String, BigDecimal>,
    ): Verdict? {
        val present = attached.filter { it in ready }
        val covered = present.any { it.strategy == null } || present.mapNotNull { it.strategy }.containsAll(expected)
        if (!covered || present.isEmpty()) return null
        val held = HashMap<String, BigDecimal>()
        for (broker in present) {
            for (symbol in broker.positions.symbols()) {
                val code = symbols.code(symbol) ?: continue
                held[code] = (held[code] ?: BigDecimal.ZERO).add(broker.holding(symbol))
            }
        }
        val differ =
            (held.keys + account.keys).filter { code ->
                (held[code] ?: BigDecimal.ZERO).compareTo(account[code] ?: BigDecimal.ZERO) != 0
            }
        if (differ.isEmpty()) return Verdict(null)
        val amount = { of: Map<String, BigDecimal>, code: String -> (of[code] ?: BigDecimal.ZERO).toPlainString() }
        return Verdict(
            differ.joinToString("; ", prefix = "strategies hold, account holds: ") { code ->
                "$code ${amount(held, code)} vs ${amount(account, code)}"
            },
        )
    }
}
