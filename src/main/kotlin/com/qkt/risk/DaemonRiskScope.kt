package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * What the sessions of one daemon share so a global limit binds all of them. Each deployed strategy
 * runs its own session and [RiskState]; on its own, `risk.max_daily_loss: 8` would then allow 8 a
 * strategy. Sessions report their day's realized P&L here and read the sum, e.g. -5 and -3.05 read
 * -8.05 from either side, and a global rule's breach is visible to every other session. Called from
 * each session's engine thread, on every tick: unchanged figures take no lock and scan nothing.
 */
class DaemonRiskScope(
    private val clock: Clock = SystemClock(),
) {
    private class Reported(
        val epochDay: Long,
        val realized: BigDecimal,
    )

    private class Breach(
        val session: String,
        val epochDay: Long,
        val decision: HaltDecision.Halt,
    )

    private val reported = ConcurrentHashMap<String, Reported>()
    private val breaches = ConcurrentHashMap<Any, Breach>()

    @Volatile
    private var total: Reported = Reported(Long.MIN_VALUE, BigDecimal.ZERO)

    /** Records [session]'s realized P&L for today, [realized], and returns the whole daemon's. */
    fun realizedToday(
        session: String,
        realized: BigDecimal,
    ): BigDecimal {
        val today = today()
        val mine = reported[session]
        if (mine == null || mine.epochDay != today || mine.realized.compareTo(realized) != 0) {
            reported[session] = Reported(today, realized)
            recount(today)
        } else if (total.epochDay != today) {
            recount(today)
        }
        return total.realized
    }

    @Synchronized
    private fun recount(today: Long) {
        val sum = reported.values.filter { it.epochDay == today }.fold(BigDecimal.ZERO) { acc, r -> acc + r.realized }
        total = Reported(today, sum)
    }

    /** Records what [rule] of [session] decided: a global breach stands until it decides otherwise. */
    fun decided(
        session: String,
        rule: Any,
        decision: HaltDecision,
    ) {
        if (decision is HaltDecision.Halt && decision.strategyId == null) {
            breaches[rule] = Breach(session, today(), decision)
        } else if (breaches.isNotEmpty()) {
            breaches.remove(rule)
        }
    }

    /** A breach standing in a session other than [session], or null; a DAILY one ends with its UTC day. */
    fun breachElsewhere(session: String): HaltDecision.Halt? {
        if (breaches.isEmpty()) return null
        val today = today()
        return breaches.values
            .firstOrNull {
                it.session != session && (it.decision.scope != HaltScope.DAILY || it.epochDay == today)
            }?.decision
    }

    private fun today(): Long = Math.floorDiv(clock.now(), MILLIS_PER_DAY)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
