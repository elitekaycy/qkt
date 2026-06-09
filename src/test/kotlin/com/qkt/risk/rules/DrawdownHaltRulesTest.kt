package com.qkt.risk.rules

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.FakePnL
import com.qkt.risk.HaltDecision
import com.qkt.risk.RiskState
import com.qkt.risk.TestClock
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownHaltRulesTest {
    private fun riskState(
        realized: String,
        initialBalance: String = "10000",
    ): Pair<RiskState, FakePnL> {
        val clock = TestClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val pnl = FakePnL(BigDecimal(realized), BigDecimal("0"))
        val rs =
            RiskState(
                pnl,
                StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
                clock,
                bus,
                BigDecimal(initialBalance),
                DailyDrawdownBasis.BALANCE,
            )
        rs.onTick()
        return rs to pnl
    }

    @Test
    fun `static total drawdown halts when loss exceeds the limit`() {
        val (rs, _) = riskState("-900") // 9% of 10000 > 8%
        val rule = MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("10000"))
        assertThat(rule.evaluate(rs)).isInstanceOf(HaltDecision.Halt::class.java)
    }

    @Test
    fun `static total drawdown continues under the limit`() {
        val (rs, _) = riskState("-500") // 5% < 8%
        val rule = MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("10000"))
        assertThat(rule.evaluate(rs)).isEqualTo(HaltDecision.Continue)
    }

    @Test
    fun `daily drawdown halts after an intraday loss past the limit`() {
        val (rs, pnl) = riskState("0")
        val rule = MaxDailyDrawdown(BigDecimal("0.04"))
        assertThat(rule.evaluate(rs)).isEqualTo(HaltDecision.Continue) // captures day-start ref 10000
        pnl.realized = BigDecimal("-500") // intraday loss => 5% > 4%
        rs.onTick()
        assertThat(rule.evaluate(rs)).isInstanceOf(HaltDecision.Halt::class.java)
    }
}
