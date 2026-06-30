package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-strategy realized P&L bucketed by UTC day. Backs [RiskView.realizedToday]
 * and the daily-loss halt rules.
 *
 * The "day" boundary is computed off the injected [Clock], so backtests with a
 * [com.qkt.common.FixedClock] get deterministic day-rollover behaviour.
 */
class DailyPnLTracker(
    private val clock: Clock,
) {
    private val byStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    @Volatile
    private var globalToday: BigDecimal = Money.ZERO

    @Volatile
    private var lastResetEpochDay: Long = epochDay()

    @Synchronized
    fun recordRealized(
        strategyId: String,
        realized: BigDecimal,
    ) {
        rolloverIfNeeded()
        if (strategyId.isNotBlank()) {
            val current = byStrategy[strategyId] ?: Money.ZERO
            byStrategy[strategyId] = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
        }
        globalToday = globalToday.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun globalRealizedToday(): BigDecimal {
        rolloverIfNeeded()
        return globalToday
    }

    fun realizedToday(strategyId: String): BigDecimal {
        rolloverIfNeeded()
        return byStrategy[strategyId] ?: Money.ZERO
    }

    /** The day's state for persistence: (epochDay, global, per-strategy). */
    @Synchronized
    fun snapshot(): DailyPnLSnapshot {
        rolloverIfNeeded()
        return DailyPnLSnapshot(lastResetEpochDay, globalToday, byStrategy.toMap())
    }

    /**
     * Restore a persisted day. A snapshot from a PAST day is discarded — the new day's
     * budget legitimately starts fresh at UTC midnight; only same-day state carries over.
     */
    @Synchronized
    fun restore(snapshot: DailyPnLSnapshot) {
        if (snapshot.epochDay != epochDay()) return
        lastResetEpochDay = snapshot.epochDay
        globalToday = snapshot.global
        byStrategy.clear()
        byStrategy.putAll(snapshot.byStrategy)
    }

    @Synchronized
    private fun rolloverIfNeeded() {
        val today = epochDay()
        if (today != lastResetEpochDay) {
            byStrategy.clear()
            globalToday = Money.ZERO
            lastResetEpochDay = today
        }
    }

    // UTC epoch-day = floor(epochMillis / millis-per-day). Identical to building an Instant ->
    // ZonedDateTime(UTC) -> LocalDate -> toEpochDay(), but without the per-tick java.time allocations
    // that chain incurred on the hot path (rolloverIfNeeded runs every tick).
    private fun epochDay(): Long = Math.floorDiv(clock.now(), MILLIS_PER_DAY)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/** Value snapshot of one UTC day's realized PnL. */
data class DailyPnLSnapshot(
    val epochDay: Long,
    val global: BigDecimal,
    val byStrategy: Map<String, BigDecimal>,
)
