package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskStateSnapshotTest {
    private val clock = FixedClock(time = 1_790_000_000_000L)
    private val riskState =
        StrategyPositionTracker().let { positions ->
            val prices = MarketPriceTracker()
            RiskState(
                PnLCalculator(positions.account, prices),
                StrategyPnL(positions, prices),
                clock,
                EventBus(clock, MonotonicSequenceGenerator()),
            )
        }

    private fun payload(strategyId: String) = riskSnapshotOf(riskState, strategyId, ts = 1L).payload

    @Test
    fun `a session halted by a global rule says so for its strategy, with the rule and how it clears`() {
        riskState.halt("global drawdown 0.1004 exceeds max 0.1", HaltScope.PERSISTENT)

        assertThat(payload("eurusd_rsi_fade"))
            .containsEntry("halted", true)
            .containsEntry("haltReason", "global drawdown 0.1004 exceeds max 0.1")
            .containsEntry("haltScope", "PERSISTENT")
            .containsEntry("haltPersistent", true)
            .containsEntry("haltedAt", 1_790_000_000_000L)
    }

    @Test
    fun `a strategy-scoped halt is reported for that strategy only`() {
        riskState.haltStrategy("silver_ema_cross", "strategy drawdown 0.0896 exceeds max 0.05", HaltScope.DAILY)

        assertThat(payload("silver_ema_cross")).containsEntry("halted", true).containsEntry("haltScope", "DAILY")
        assertThat(payload("gold_ema_pullback")).containsEntry("halted", false).containsEntry("haltReason", null)
    }

    @Test
    fun `a session that is not halted says so, which clears any older halt a dashboard still holds`() {
        assertThat(payload("gold_ema_pullback"))
            .containsEntry("halted", false)
            .containsEntry("haltScope", null)
        assertThat(riskSnapshotOf(riskState, "gold_ema_pullback", ts = 1L).type).isEqualTo("risk.snapshot")
    }
}
