package com.qkt.risk

import java.math.BigDecimal
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-strategy pacing state for overtrading controls: entry fills today, consecutive losing
 * closes, and the timestamp of the most recent loss.
 */
class PacerLedger {
    private val entryFillsByStrategy: MutableMap<String, ArrayDeque<Long>> = ConcurrentHashMap()
    private val lossStreakByStrategy: MutableMap<String, Int> = ConcurrentHashMap()
    private val lastLossAtByStrategy: MutableMap<String, Long> = ConcurrentHashMap()

    /** Record a risk-increasing fill for [strategyId]. */
    fun recordEntryFill(
        strategyId: String,
        timestampMs: Long,
    ) {
        if (strategyId.isBlank()) return
        val q = entryFillsByStrategy.getOrPut(strategyId) { ArrayDeque() }
        synchronized(q) {
            q.addLast(timestampMs)
        }
    }

    /** Record a closed-trade outcome. Positive resets losses; negative increments them. */
    fun recordOutcome(
        strategyId: String,
        timestampMs: Long,
        pnl: BigDecimal,
    ) {
        if (strategyId.isBlank() || pnl.signum() == 0) return
        if (pnl.signum() > 0) {
            lossStreakByStrategy[strategyId] = 0
        } else {
            lossStreakByStrategy[strategyId] = lossStreak(strategyId) + 1
            lastLossAtByStrategy[strategyId] = timestampMs
        }
    }

    /** Entry fills since UTC midnight for [nowMs]. */
    fun tradesToday(
        strategyId: String,
        nowMs: Long,
    ): Int {
        val q = entryFillsByStrategy[strategyId] ?: return 0
        val cutoff = utcMidnight(nowMs)
        synchronized(q) {
            while (q.isNotEmpty() && q.peekFirst() < cutoff) q.removeFirst()
            return q.size
        }
    }

    /** Consecutive losing closed outcomes for [strategyId]. */
    fun lossStreak(strategyId: String): Int = lossStreakByStrategy[strategyId] ?: 0

    /** Remaining cooldown in milliseconds for [strategyId], or zero when inactive. */
    fun cooldownRemainingMs(
        strategyId: String,
        nowMs: Long,
        durationMs: Long,
        afterConsecutive: Int = 1,
    ): Long {
        require(durationMs > 0) { "durationMs must be > 0: $durationMs" }
        require(afterConsecutive > 0) { "afterConsecutive must be > 0: $afterConsecutive" }
        if (lossStreak(strategyId) < afterConsecutive) return 0L
        val lastLossAt = lastLossAtByStrategy[strategyId] ?: return 0L
        return (lastLossAt + durationMs - nowMs).coerceAtLeast(0L)
    }

    private fun utcMidnight(nowMs: Long): Long = Math.floorDiv(nowMs, DAY_MS) * DAY_MS

    private companion object {
        private const val DAY_MS = 86_400_000L
    }
}

/** Read-only pacing state exposed to strategy expressions. */
interface PacerView {
    /** Entry fills recorded since UTC midnight for [nowMs]. */
    fun tradesToday(nowMs: Long): Int

    /** Active cooldown remaining in whole seconds for [nowMs], or zero when inactive. */
    fun cooldownRemainingSeconds(nowMs: Long): Long
}

/** Per-strategy read-only view over [PacerLedger] used by DSL state accessors. */
class PacerViewImpl(
    private val ledger: PacerLedger,
    private val strategyId: String,
    private val cooldownDurationMs: Long?,
    private val cooldownAfterConsecutive: Int = 1,
) : PacerView {
    override fun tradesToday(nowMs: Long): Int = ledger.tradesToday(strategyId, nowMs)

    override fun cooldownRemainingSeconds(nowMs: Long): Long {
        val duration = cooldownDurationMs ?: return 0L
        return ledger.cooldownRemainingMs(strategyId, nowMs, duration, cooldownAfterConsecutive) / 1_000L
    }
}

/** No-op pacing view for tests and contexts that do not enable pacer controls. */
class NoOpPacerView : PacerView {
    override fun tradesToday(nowMs: Long): Int = 0

    override fun cooldownRemainingSeconds(nowMs: Long): Long = 0
}
