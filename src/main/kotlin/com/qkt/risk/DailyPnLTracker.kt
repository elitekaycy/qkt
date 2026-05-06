package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class DailyPnLTracker(private val clock: Clock) {
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

    @Synchronized
    private fun rolloverIfNeeded() {
        val today = epochDay()
        if (today != lastResetEpochDay) {
            byStrategy.clear()
            globalToday = Money.ZERO
            lastResetEpochDay = today
        }
    }

    private fun epochDay(): Long =
        Instant
            .ofEpochMilli(clock.now())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()
}
