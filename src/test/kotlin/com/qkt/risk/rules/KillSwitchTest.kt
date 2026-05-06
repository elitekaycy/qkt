package com.qkt.risk.rules

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KillSwitchTest {
    private fun newState(): RiskState {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        return RiskState(pnl, strategyPnL, clock, bus)
    }

    private val anyRequest =
        OrderRequest.Market(
            id = "c1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "A",
        )

    @Test
    fun `Approve when not halted`() {
        val state = newState()
        val rule = KillSwitch(state)

        assertThat(rule.evaluate(anyRequest, PositionTracker())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `Reject when globally halted`() {
        val state = newState()
        state.halt("test")
        val rule = KillSwitch(state)

        val decision = rule.evaluate(anyRequest, PositionTracker())
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("test")
    }

    @Test
    fun `Reject when strategy halted`() {
        val state = newState()
        state.haltStrategy("A", "strategy halt")
        val rule = KillSwitch(state)

        assertThat(rule.evaluate(anyRequest, PositionTracker())).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `Approve when other strategy halted`() {
        val state = newState()
        state.haltStrategy("B", "B halt")
        val rule = KillSwitch(state)

        assertThat(rule.evaluate(anyRequest, PositionTracker())).isEqualTo(Decision.Approve)
    }
}
