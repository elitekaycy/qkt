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
    /**
     * Account starting balance — the anchor that makes the series true equity. A 0-based
     * PnL series makes trailing drawdown a fraction of peak PROFIT, not equity: a $100
     * giveback off a $200 peak profit on a $10k account would read as 50% instead of 1%.
     */
    private val startingBalance: BigDecimal = Money.ZERO,
) {
    @Volatile
    private var currentTotalEquity: BigDecimal = startingBalance

    @Volatile
    private var peakTotalEquity: BigDecimal = startingBalance

    private val perStrategyCurrent: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private val perStrategyPeak: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun update() {
        val total = startingBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        currentTotalEquity = total
        if (total > peakTotalEquity) peakTotalEquity = total
    }

    fun updateStrategy(strategyId: String) {
        if (strategyId.isBlank()) return
        // equityFor anchors at the strategy's own starting balance (set on every deploy path).
        val total = strategyPnL.equityFor(strategyId)
        perStrategyCurrent[strategyId] = total
        val peak = perStrategyPeak[strategyId]
        if (peak == null || total > peak) perStrategyPeak[strategyId] = total
    }

    /**
     * Refresh current + peak equity for every strategy already being tracked. Driven from the
     * engine tick so per-strategy peak captures intra-tick unrealized highs between fills — the
     * same way [update] tracks the global peak. Keeps `MaxStrategyDrawdown` measuring drawdown
     * against a live peak instead of a stale fill-point one.
     */
    fun updateStrategies() {
        for (strategyId in perStrategyCurrent.keys) {
            updateStrategy(strategyId)
        }
    }

    fun currentEquity(): BigDecimal = currentTotalEquity

    fun peakEquity(): BigDecimal = peakTotalEquity

    fun currentEquityFor(strategyId: String): BigDecimal =
        perStrategyCurrent[strategyId] ?: strategyPnL.equityFor(strategyId)

    fun peakEquityFor(strategyId: String): BigDecimal =
        perStrategyPeak[strategyId] ?: strategyPnL.equityFor(strategyId)

    /** The strategy's equity anchor — its starting balance as registered with [StrategyPnL]. */
    fun startingBalanceFor(strategyId: String): BigDecimal = strategyPnL.startingBalanceFor(strategyId)
}
