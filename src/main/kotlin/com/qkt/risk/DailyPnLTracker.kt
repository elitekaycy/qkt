package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-strategy realized P&L bucketed by a UTC period — the UTC day by default. Backs
 * [RiskView.realizedToday] and the daily-loss halt rules; a second instance keyed by
 * [PnLPeriod.UTC_MONTH] backs [RiskView.realizedMonth].
 *
 * The period boundary is computed off the injected [Clock], so backtests with a
 * [com.qkt.common.FixedClock] get deterministic rollover behaviour.
 */
class DailyPnLTracker(
    private val clock: Clock,
    private val period: PnLPeriod = PnLPeriod.UTC_DAY,
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

    /** The current period's state for persistence: (period key, global, per-strategy). */
    @Synchronized
    fun snapshot(): DailyPnLSnapshot {
        rolloverIfNeeded()
        return DailyPnLSnapshot(lastResetEpochDay, globalToday, byStrategy.toMap())
    }

    /**
     * Restore a persisted period. A snapshot from a PAST period is discarded — the new
     * period's budget legitimately starts fresh at its UTC boundary; only same-period
     * state carries over.
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

    private fun epochDay(): Long = period.keyOf(clock.now())
}

/** The UTC calendar period a [DailyPnLTracker] buckets realized P&L by. */
enum class PnLPeriod {
    UTC_DAY,
    UTC_MONTH,
    ;

    /** A key that changes exactly when [epochMs] crosses into the next period. */
    fun keyOf(epochMs: Long): Long =
        when (this) {
            // floor(epochMillis / millis-per-day): identical to Instant -> LocalDate.toEpochDay()
            // without the per-tick java.time allocations (rolloverIfNeeded runs every tick).
            UTC_DAY -> Math.floorDiv(epochMs, MILLIS_PER_DAY)
            // Months since 1970-01; LocalDate only here, where the day key changed or on read.
            UTC_MONTH -> {
                val date = java.time.LocalDate.ofEpochDay(Math.floorDiv(epochMs, MILLIS_PER_DAY))
                (date.year - 1970L) * 12L + (date.monthValue - 1)
            }
        }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/** Value snapshot of one period's realized PnL; [epochDay] is the period key. */
data class DailyPnLSnapshot(
    val epochDay: Long,
    val global: BigDecimal,
    val byStrategy: Map<String, BigDecimal>,
)
