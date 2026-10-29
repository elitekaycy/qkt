package com.qkt.connector.gateway

import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The account-level check of a gateway account: once every strategy expected on the account
 * ([expectedStrategies] plus those attached to [routing]) has a restored, ready session, what their
 * sessions hold must add up, symbol by symbol, to what [account] holds at the venue. Per-strategy
 * holdings cannot be checked against a netting venue, which only knows the total. An account [shared]
 * with other holders is judged by [GatewayOtherHolders] instead: only on the codes its strategies trade,
 * and only for moves their fills do not explain. Every call takes [lock].
 */
internal class GatewayHolders(
    private val symbols: GatewaySymbols,
    private val routing: GatewayRouting,
    private val account: GatewayAccountState,
    private val lock: Any,
    private val expectedStrategies: () -> Set<String>,
    shared: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(GatewayHolders::class.java)
    private val ready = HashSet<GatewayRouting.Attached>()
    private val others = if (shared) GatewayOtherHolders() else null

    /** Why no order that adds risk may be sent (holdings disagreed with the account when last judged), or null. */
    @Volatile var riskRefused: String? = null
        private set

    /** [broker]'s session has restored its positions: judge the account again. */
    fun ready(broker: GatewayRouting.Attached) =
        synchronized(lock) {
            ready += broker
            judge()
        }

    /**
     * Judges the account now ([check]) and keeps the verdict in [riskRefused], which it returns; while
     * some expected strategy is not ready, the last verdict stands.
     */
    fun judge(): String? =
        synchronized(lock) {
            val expected = expectedStrategies() + routing.brokers.mapNotNull { it.strategy }
            val verdict = check(routing.brokers, expected, account.positions) ?: return riskRefused
            if (verdict.mismatch != null && verdict.mismatch != riskRefused) {
                log.error("gateway account check failed: {}", verdict.mismatch)
            }
            if (verdict.mismatch == null && riskRefused != null) log.info("gateway account check agrees again")
            riskRefused = verdict.mismatch
            riskRefused
        }

    /** An order on venue [code] is about to be sent: on a shared account, what others hold of it is noted first. */
    fun trading(code: String) {
        val others = others ?: return
        synchronized(lock) {
            val held = routing.brokers.fold(BigDecimal.ZERO) { sum, b -> sum.add(b.holding(symbols.qkt(code))) }
            others.enter(code, account.quantity(code), held)
        }
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
    private fun check(
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
        others?.let { return Verdict(it.mismatch(held, account)) }
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
