package com.qkt.app

import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.pnl.TradeHistory
import com.qkt.positions.Position
import com.qkt.risk.PacerLedger
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.risk.RunawayBreaker
import java.math.BigDecimal

/**
 * The only writer of every realized accumulator: account and strategy PnL, trade history, the
 * pacer, the runaway breaker and the risk state. The pipeline subscribes it first on
 * [FillAccountedEvent], so equity, daily loss and halt rules reflect an accounted amount before
 * any later subscriber acts.
 */
internal class AccountedFillFold(
    private val pnl: PnLCalculator,
    private val strategyPnL: StrategyPnL,
    private val tradeHistory: TradeHistory,
    private val pacerLedger: PacerLedger,
    private val runawayBreaker: RunawayBreaker?,
    private val riskState: RiskState,
    private val riskEngine: RiskEngine,
) {
    /** Fold one accounted amount into every accumulator, then re-evaluate halt rules. */
    fun fold(a: FillAccountedEvent) {
        pnl.recordRealized(a.netAccountRealized)
        strategyPnL.recordRealized(a.strategyId, a.netStrategyAccountRealized)
        if (a.kind == FillAccountingKind.EXECUTION) {
            tradeHistory.recordTrade(a.strategyId, a.executedAt, a.netStrategyAccountRealized, a.symbol)
            if (isRiskIncreasingFill(a.strategyPositionBefore, a.strategyPositionAfter)) {
                pacerLedger.recordEntryFill(a.strategyId, a.executedAt)
            }
            if (a.reducedExposure) {
                pacerLedger.recordOutcome(a.strategyId, a.executedAt, a.netStrategyAccountRealized)
            }
            if (a.netStrategyAccountRealized.signum() != 0) runawayBreaker?.recordClose(a.strategyId)
        }
        // A boot-time reconcile is venue history from before this session; it belongs in
        // lifetime P&L, not in today's loss budget.
        if (a.kind != FillAccountingKind.RECONCILE) riskState.onFill(a.strategyId, a.netStrategyAccountRealized)
        riskEngine.evaluateHaltRules()
    }
}

private fun isRiskIncreasingFill(
    before: Position?,
    after: Position?,
): Boolean {
    val beforeQty = before?.quantity ?: BigDecimal.ZERO
    val afterQty = after?.quantity ?: BigDecimal.ZERO
    val flipped = beforeQty.signum() != 0 && afterQty.signum() != 0 && beforeQty.signum() != afterQty.signum()
    return afterQty.abs() > beforeQty.abs() || flipped
}
