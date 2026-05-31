package com.qkt.risk

import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks total and per-strategy equity (current + peak-since-session-start). Fed by
 * P&L deltas from [PnLProvider] / [StrategyPnL]; exposes both current and peak readings
 * for [DrawdownTracker] to compute drawdown off.
 *
 * Thread-safe — `@Volatile` totals plus a [ConcurrentHashMap] for per-strategy peaks.
 */
class EquityTracker(
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
) {
    @Volatile
    private var currentTotalEquity: BigDecimal = Money.ZERO

    @Volatile
    private var peakTotalEquity: BigDecimal = Money.ZERO

    private val perStrategyCurrent: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private val perStrategyPeak: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun update() {
        val total = pnl.realizedTotal().add(pnl.unrealizedTotal())
        currentTotalEquity = total
        if (total > peakTotalEquity) peakTotalEquity = total
    }

    fun updateStrategy(strategyId: String) {
        if (strategyId.isBlank()) return
        val total = strategyPnL.totalFor(strategyId)
        perStrategyCurrent[strategyId] = total
        val peak = perStrategyPeak[strategyId] ?: Money.ZERO
        if (total > peak) perStrategyPeak[strategyId] = total
    }

    fun currentEquity(): BigDecimal = currentTotalEquity

    fun peakEquity(): BigDecimal = peakTotalEquity

    fun currentEquityFor(strategyId: String): BigDecimal =
        perStrategyCurrent[strategyId] ?: strategyPnL.totalFor(strategyId)

    fun peakEquityFor(strategyId: String): BigDecimal = perStrategyPeak[strategyId] ?: Money.ZERO
}
