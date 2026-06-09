package com.qkt.cli.daemon.portfolio

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.FakePnL
import com.qkt.risk.RiskState
import com.qkt.risk.TestClock
import com.qkt.risk.rules.MaxDailyDrawdown
import com.qkt.risk.rules.MaxDrawdown
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioRiskAggregatorTest {
    private class FakeChild : ChildRiskTarget {
        var flattened = 0
        var halted: String? = null

        override fun flatten() {
            flattened++
        }

        override fun halt(reason: String) {
            halted = reason
        }
    }

    private fun bookRiskState(pnl: FakePnL): RiskState {
        val clock = TestClock(0L)
        return RiskState(
            pnl,
            StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            BigDecimal("100000"),
            DailyDrawdownBasis.BALANCE,
        )
    }

    @Test
    fun `flattens and halts every child on a static total breach, once`() {
        val rs = bookRiskState(FakePnL(BigDecimal("-9000"), BigDecimal.ZERO)) // 9% of 100000 > 8%
        val a = FakeChild()
        val b = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                children = listOf(a, b),
                bookRiskState = rs,
                haltRules = listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
            )

        agg.evaluate()
        agg.evaluate() // latched — second call is a no-op

        assertThat(a.flattened).isEqualTo(1)
        assertThat(b.flattened).isEqualTo(1)
        assertThat(a.halted).contains("drawdown")
        assertThat(b.halted).isNotNull()
    }

    @Test
    fun `daily breach also flattens and halts`() {
        val pnl = FakePnL(BigDecimal.ZERO, BigDecimal.ZERO)
        val rs = bookRiskState(pnl)
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(listOf(a), rs, listOf(MaxDailyDrawdown(BigDecimal("0.04"))))

        agg.evaluate() // captures day-start ref at 100000 (Continue)
        assertThat(a.halted).isNull()
        pnl.realized = BigDecimal("-5000") // 5% intraday > 4%
        agg.evaluate()

        assertThat(a.flattened).isEqualTo(1)
        assertThat(a.halted).isNotNull()
    }

    @Test
    fun `does nothing while under the limit`() {
        val rs = bookRiskState(FakePnL(BigDecimal("-1000"), BigDecimal.ZERO)) // 1% < 8%
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                listOf(a),
                rs,
                listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
            )
        agg.evaluate()
        assertThat(a.flattened).isEqualTo(0)
        assertThat(a.halted).isNull()
    }
}
