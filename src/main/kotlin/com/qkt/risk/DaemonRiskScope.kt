package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedRiskState
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * What the sessions of one daemon share so a global limit binds all of them. Each deployed strategy
 * runs its own session and [RiskState]; on its own, `risk.max_daily_loss: 8` would then allow 8 a
 * strategy. Sessions report their day's realized P&L here and read the sum, e.g. -5 and -3.05 read
 * -8.05 from either side, and a global rule's breach is visible to every other session. Called from
 * each session's engine thread, on every tick: unchanged figures take no lock and scan nothing.
 *
 * With a hard limit, or on a day it mixes standalones and portfolios, the daemon keeps a record of this under
 * [RECORD_OWNER]: a restart then reads the whole total before all its sessions are back, and any hard halt.
 * Without either it writes and reads nothing, as a daemon always has.
 */
class DaemonRiskScope(
    val clock: Clock = SystemClock(),
    private val persistor: StatePersistor = NoopStatePersistor(),
    /** `risk.hard_daily_loss`; null is off. */
    val hardDailyLoss: BigDecimal? = null,
) {
    /** Whether this daemon mixes standalones and portfolios today. */
    val composition = DaemonComposition(clock)

    @Volatile
    private var hardHalt: Pair<Long, String>? = null

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
            persistIfShared(today)
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

    /** The whole daemon's realized P&L today, as last reported. */
    fun totalToday(): BigDecimal {
        val today = today()
        if (total.epochDay != today) recount(today)
        return total.realized
    }

    /** A standalone is about to deploy; see [DaemonComposition]. */
    fun noteStandalone() {
        composition.noteStandalone()
        persistIfShared(today())
    }

    /** A portfolio is about to deploy; see [DaemonComposition]. */
    fun noteBook() {
        composition.noteBook()
        persistIfShared(today())
    }

    /**
     * Why new entries are blocked for the whole daemon right now, or null: the hard limit stands, or the mixed
     * daemon's total crossed [maxLoss] (zero is off). Only a mixed day reads the total; otherwise this is two volatile reads.
     */
    fun entryBlockReason(maxLoss: BigDecimal): String? {
        hardHaltReason()?.let { return it }
        if (maxLoss.signum() <= 0 || !composition.mixed()) return null
        val loss = totalToday().negate()
        return if (loss > maxLoss) "daily loss $loss exceeds max $maxLoss across the daemon" else null
    }

    /** The reason the hard limit tripped today, or null. A day later it has ended by itself. */
    fun hardHaltReason(): String? = hardHalt?.takeIf { it.first == today() }?.second

    /** Latches the hard limit for today and records it, before anything is flattened. */
    fun latchHardHalt(reason: String) {
        val today = today()
        hardHalt = today to reason
        persist(today)
    }

    /**
     * Reads the daemon's record, once, before any session deploys. A record of an earlier day is the past: its
     * total is a new day's zero. A record of today seeds every session's figure, including one not deployed yet
     * or undeployed since, and stands only for what is still on: with the hard limit gone from the config and a
     * day that was not mixed, it reads as nothing.
     */
    fun restore() {
        val record = persistor.loadRiskState(RECORD_OWNER) ?: return
        val today = today()
        if (record.epochDay != today) return
        val wasMixed = record.daemonMixedEpochDay == today
        if (hardDailyLoss == null && !wasMixed) return
        if (wasMixed) composition.restoreMixed(today)
        for ((session, realized) in record.perStrategyRealizedToday) reported[session] = Reported(today, realized)
        recount(today)
        if (hardDailyLoss != null && record.halted) {
            hardHalt = today to (record.haltReason ?: "hard_daily_loss tripped before this restart")
        }
    }

    private fun persistIfShared(today: Long) {
        if (hardDailyLoss != null || composition.mixed()) persist(today)
    }

    @Synchronized
    private fun persist(today: Long) {
        val hard = hardHaltReason()
        persistor.saveRiskState(
            RECORD_OWNER,
            PersistedRiskState(
                epochDay = today,
                realizedToday = total.realized,
                perStrategyRealizedToday =
                    reported.entries.filter { it.value.epochDay == today }.associate { it.key to it.value.realized },
                halted = hard != null,
                haltReason = hard,
                haltScope = HaltScope.DAILY.name,
                haltEpochDay = today,
                strategyHalts = emptyList(),
                daemonMixedEpochDay = composition.mixedEpochDay(),
            ),
        )
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

    companion object {
        /** The owner of the daemon's risk record in the state persistor; no strategy is named this. */
        const val RECORD_OWNER = "__daemon__"
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
