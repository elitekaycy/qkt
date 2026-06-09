package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily drawdown measured against a reference captured at UTC midnight. The reference is the
 * day-start balance (closed) or equity (including open-position float) per [basis]; the drawdown is
 * measured against current equity: `max(0, (reference − currentEquity) / reference)`. Mirrors
 * [DailyPnLTracker]'s UTC-day rollover, so a backtest with a deterministic clock rolls over
 * deterministically.
 *
 * Global equity is absolute: `initialBalance + realized + unrealized`. Per-strategy uses
 * [StrategyPnL]'s already-absolute `balanceFor` / `equityFor`. The reference is captured lazily on
 * the first query of a new day; the risk engine queries every fill/tick, so that lands ~midnight.
 */
class DailyDrawdownTracker(
    private val clock: Clock,
    private val basis: DailyDrawdownBasis,
    private val initialBalance: BigDecimal,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
) {
    @Volatile
    private var lastResetEpochDay: Long = epochDay()

    @Volatile
    private var globalRef: BigDecimal? = null

    private val strategyRef: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun globalDrawdownToday(): BigDecimal {
        rolloverIfNeeded()
        val ref = globalRef ?: captureGlobalRef().also { globalRef = it }
        val current = initialBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        return ddFraction(ref, current)
    }

    fun strategyDrawdownToday(strategyId: String): BigDecimal {
        rolloverIfNeeded()
        val ref = strategyRef.getOrPut(strategyId) { captureStrategyRef(strategyId) }
        return ddFraction(ref, strategyPnL.equityFor(strategyId))
    }

    private fun captureGlobalRef(): BigDecimal {
        val float = if (basis == DailyDrawdownBasis.EQUITY) pnl.unrealizedTotal() else Money.ZERO
        return initialBalance.add(pnl.realizedTotal()).add(float)
    }

    private fun captureStrategyRef(strategyId: String): BigDecimal =
        if (basis == DailyDrawdownBasis.EQUITY) {
            strategyPnL.equityFor(strategyId)
        } else {
            strategyPnL.balanceFor(strategyId)
        }

    private fun ddFraction(
        ref: BigDecimal,
        current: BigDecimal,
    ): BigDecimal {
        if (ref.signum() <= 0 || current >= ref) return Money.ZERO
        return ref.subtract(current).divide(ref, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    @Synchronized
    private fun rolloverIfNeeded() {
        val today = epochDay()
        if (today != lastResetEpochDay) {
            globalRef = null
            strategyRef.clear()
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
