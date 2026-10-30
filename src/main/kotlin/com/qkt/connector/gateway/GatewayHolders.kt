package com.qkt.connector.gateway

import com.qkt.common.Clock
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The account-level check of a gateway account: once every strategy expected on the account
 * ([expectedStrategies] plus those attached to [routing]) has a restored, ready session, and every broker attached
 * now is ready (a strategy's stream lanes attach beside its session's broker and hold its contracts), what their
 * sessions hold must add up, symbol by symbol, to what [account] holds at the venue. Per-strategy
 * holdings cannot be checked against a netting venue, which only knows the total. An account [shared]
 * with other holders is judged by [GatewayOtherHolders] instead: only on the codes its strategies trade,
 * and only for moves their fills do not explain. Every call takes [lock].
 *
 * While [deploysUnderway] the daemon is still working through its startup deploys, one at a time, so a strategy
 * that holds on the account may not even be known yet: nothing is judged until the daemon says it is through
 * ([judge]). A strategy that failed to deploy is then simply missing, and the difference it leaves is refused until
 * its retry lands. Nothing holds the check back for ever: [HOLD_LIMIT_MS] after a broker last attached or got ready,
 * those still missing no longer count, and the first order adding risk after that is judged before it is sent
 * ([refusal]) with whoever is ready.
 */
internal class GatewayHolders(
    private val symbols: GatewaySymbols,
    private val routing: GatewayRouting,
    private val account: GatewayAccountState,
    private val lock: Any,
    private val expectedStrategies: () -> Set<String>,
    shared: Boolean = false,
    private val clock: Clock,
    private val deploysUnderway: () -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger(GatewayHolders::class.java)
    private val ready = HashSet<GatewayRouting.Attached>()
    private val others = if (shared) GatewayOtherHolders() else null
    private var lastArrivalMs = clock.now()

    /** The last judgement was put off, so what the account holds has not been judged since. */
    @Volatile private var owed = false

    /** Why no order that adds risk may be sent (holdings disagreed with the account when last judged), or null. */
    @Volatile var riskRefused: String? = null
        private set

    /** A broker is attaching: its session gets the whole limit to get ready. */
    fun attaching() =
        synchronized(lock) {
            lastArrivalMs = clock.now()
        }

    /** [broker]'s session has restored its positions: judge the account again. */
    fun ready(broker: GatewayRouting.Attached) =
        synchronized(lock) {
            ready += broker
            lastArrivalMs = clock.now()
            judge()
        }

    /** Why an order adding risk is refused now, judging first when a judgement is owed or the last one refused. */
    fun refusal(): String? = if (riskRefused == null && !owed) null else judge()

    /**
     * Judges the account now ([check]) and keeps the verdict in [riskRefused], which it returns; while
     * the daemon is deploying or some expected strategy is not ready, up to [HOLD_LIMIT_MS], the last verdict stands.
     */
    fun judge(): String? =
        synchronized(lock) {
            val overdue = clock.now() - lastArrivalMs >= HOLD_LIMIT_MS
            val expected = expectedStrategies() + routing.brokers.mapNotNull { it.strategy }
            val verdict =
                if (deploysUnderway() &&
                    !overdue
                ) {
                    null
                } else {
                    check(routing.brokers, expected, account.positions, overdue)
                }
            owed = verdict == null
            if (verdict == null) return riskRefused
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
     * Whether the sessions of [attached] agree with [account] (net by venue code), or null when one of them,
     * or some strategy of [expected], is not ready yet, so nothing can be judged. A broker serving every
     * strategy (null) covers them all. Once [overdue], those not ready no longer hold it back.
     */
    private fun check(
        attached: List<GatewayRouting.Attached>,
        expected: Set<String>,
        account: Map<String, BigDecimal>,
        overdue: Boolean,
    ): Verdict? {
        val present = attached.filter { it in ready }
        if (present.isEmpty()) return null
        val covered = present.any { it.strategy == null } || present.mapNotNull { it.strategy }.containsAll(expected)
        if (present.size < attached.size || !covered) {
            if (!overdue) return null
            log.warn("gateway account check judged without strategies not ready after {} ms", HOLD_LIMIT_MS)
        }
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

    companion object {
        /** How long, since a broker last attached or got ready, the check waits for those still missing. */
        const val HOLD_LIMIT_MS: Long = 10 * 60_000L
    }
}
