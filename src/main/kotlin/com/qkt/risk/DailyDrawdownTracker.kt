package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
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
    /**
     * Optional cached-equity reads. [RiskState] refreshes its [EquityTracker] at the top of every
     * engine event (tick and fill) before halt rules run, so these return the same value the
     * direct computation would — without a second full unrealized-PnL walk per tick. Null falls
     * back to computing from [pnl]/[strategyPnL] (day-start reference capture always does).
     */
    private val currentGlobalEquity: (() -> BigDecimal)? = null,
    private val currentStrategyEquity: ((String) -> BigDecimal)? = null,
) {
    @Volatile
    private var lastResetEpochDay: Long = epochDay()

    @Volatile
    private var globalRef: BigDecimal? = null

    private val strategyRef: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun globalDrawdownToday(): BigDecimal {
        rolloverIfNeeded()
        val ref = globalRef ?: captureGlobalRef().also { globalRef = it }
        val current =
            currentGlobalEquity?.invoke()
                ?: initialBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        return ddFraction(ref, current)
    }

    fun strategyDrawdownToday(strategyId: String): BigDecimal {
        rolloverIfNeeded()
        val ref = strategyRef.getOrPut(strategyId) { captureStrategyRef(strategyId) }
        val current = currentStrategyEquity?.invoke(strategyId) ?: strategyPnL.equityFor(strategyId)
        return ddFraction(ref, current)
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

    // UTC epoch-day without the per-call Instant/ZonedDateTime/LocalDate chain — rolloverIfNeeded
    // runs on every tick for daily-drawdown halt rules. Same pattern as DailyPnLTracker.
    private fun epochDay(): Long = Math.floorDiv(clock.now(), MILLIS_PER_DAY)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
