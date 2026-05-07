package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskEngineHaltRulesTest {
    @Test
    fun `evaluateHaltRules halts when a rule returns Halt`() {
        val state = newRiskState()
        state.warmupComplete = true
        val haltingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = HaltDecision.Halt("forced")
            }
        val engine = RiskEngine(emptyList(), listOf(haltingRule), PositionTracker(), state)

        engine.evaluateHaltRules()

        assertThat(state.halted).isTrue
        assertThat(state.haltReason).isEqualTo("forced")
    }

    @Test
    fun `evaluateHaltRules skips during warmup`() {
        val state = newRiskState()
        val haltingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = HaltDecision.Halt("forced")
            }
        val engine = RiskEngine(emptyList(), listOf(haltingRule), PositionTracker(), state)

        engine.evaluateHaltRules()

        assertThat(state.halted).isFalse
    }

    @Test
    fun `evaluateHaltRules tolerates rule that throws`() {
        val state = newRiskState()
        state.warmupComplete = true
        val throwingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = error("boom")
            }
        val haltingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = HaltDecision.Halt("after the throw")
            }
        val engine = RiskEngine(emptyList(), listOf(throwingRule, haltingRule), PositionTracker(), state)

        engine.evaluateHaltRules()

        assertThat(state.halted).isTrue
        assertThat(state.haltReason).isEqualTo("after the throw")
    }

    private fun newRiskState(): RiskState {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        return RiskState(pnl, strategyPnL, clock, bus)
    }
}
