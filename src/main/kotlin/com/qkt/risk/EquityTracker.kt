package com.qkt.risk

import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks total and per-strategy equity (current + persisted trailing high-water). Fed by
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

    fun update(): Boolean {
        val total = startingBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        currentTotalEquity = total
        if (total <= peakTotalEquity) return false
        peakTotalEquity = total
        return true
    }

    fun updateStrategy(strategyId: String): Boolean {
        if (strategyId.isBlank()) return false
        // equityFor anchors at the strategy's own starting balance (set on every deploy path).
        val total = strategyPnL.equityFor(strategyId)
        perStrategyCurrent[strategyId] = total
        val peak = perStrategyPeak[strategyId]
        if (peak != null && total <= peak) return false
        perStrategyPeak[strategyId] = maxOf(strategyPnL.startingBalanceFor(strategyId), total)
        return true
    }

    /**
     * Refresh current + peak equity for every strategy already being tracked. Driven from the
     * engine tick so per-strategy peak captures intra-tick unrealized highs between fills — the
     * same way [update] tracks the global peak. Keeps `MaxStrategyDrawdown` measuring drawdown
     * against a live peak instead of a stale fill-point one.
     */
    fun updateStrategies(): Boolean {
        var changed = false
        for (strategyId in perStrategyCurrent.keys) {
            if (updateStrategy(strategyId)) changed = true
        }
        return changed
    }

    fun currentEquity(): BigDecimal = currentTotalEquity

    fun peakEquity(): BigDecimal = peakTotalEquity

    fun currentEquityFor(strategyId: String): BigDecimal =
        perStrategyCurrent[strategyId] ?: strategyPnL.equityFor(strategyId)

    fun peakEquityFor(strategyId: String): BigDecimal = perStrategyPeak[strategyId] ?: strategyPnL.equityFor(strategyId)

    /** The strategy's equity anchor — its starting balance as registered with [StrategyPnL]. */
    fun startingBalanceFor(strategyId: String): BigDecimal = strategyPnL.startingBalanceFor(strategyId)

    /** Immutable trailing high-water marks for persistence. */
    fun snapshot(): EquityPeakSnapshot = EquityPeakSnapshot(peakTotalEquity, perStrategyPeak.toMap())

    /** Restore trailing high-water marks without allowing them below configured starting balances. */
    fun restore(snapshot: EquityPeakSnapshot) {
        peakTotalEquity = maxOf(startingBalance, snapshot.globalPeak)
        perStrategyPeak.clear()
        for ((strategyId, peak) in snapshot.strategyPeaks) {
            perStrategyPeak[strategyId] = maxOf(strategyPnL.startingBalanceFor(strategyId), peak)
        }
    }
}

/** Persistable global and per-strategy trailing equity peaks. */
data class EquityPeakSnapshot(
    val globalPeak: BigDecimal,
    val strategyPeaks: Map<String, BigDecimal>,
)
