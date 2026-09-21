package com.qkt.app

import com.qkt.notify.DailyRollingTracker
import com.qkt.notify.StrategySummary
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Strategy

/**
 * The per-strategy daily-summary rows for one session — equity, P&L, positions, and
 * the [dailyTracker] window totals, e.g. a strategy long 0.10 gold reads
 * `positionsSummary = "long 0.10 EXNESS:XAUUSD"` and a flat one reads `"flat"`. The daemon owns
 * one [com.qkt.notify.DailySummaryScheduler] across
 * every session; its producer calls this once per fire. Reading the rows snapshots and
 * resets the tracker, so it must be called exactly once per summary.
 */
internal class DailySummaryRows(
    private val strategies: List<Pair<String, Strategy>>,
    private val dailyTracker: DailyRollingTracker,
) {
    /** Build the rows from the engine's books; call on the engine thread. */
    fun rows(
        strategyPnL: StrategyPnL,
        strategyPositions: StrategyPositionTracker,
    ): List<StrategySummary> =
        strategies.map { (strategyId, _) ->
            val positions = strategyPositions.positionsFor(strategyId)
            val summary =
                if (positions.isEmpty() ||
                    positions.values.all { it.quantity.signum() == 0 }
                ) {
                    "flat"
                } else {
                    positions.entries.joinToString(", ") { (sym, p) ->
                        "${if (p.quantity.signum() > 0) "long" else "short"} ${p.quantity.abs().toPlainString()} $sym"
                    }
                }
            val equity = strategyPnL.equityFor(strategyId)
            val totals = dailyTracker.snapshot(strategyId, equity)
            StrategySummary(
                strategyId = strategyId,
                equity = equity,
                equityDeltaPct = totals.equityDeltaPct,
                realizedToday = strategyPnL.realizedFor(strategyId),
                unrealized = strategyPnL.unrealizedTotalFor(strategyId),
                tradesToday = totals.tradesToday,
                haltsToday = totals.haltsToday,
                positionsSummary = summary,
            )
        }
}
