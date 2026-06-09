package com.qkt.cli.daemon.portfolio

import com.qkt.bus.EventBus
import com.qkt.common.Clock
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
    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private class FakeChild : ChildRiskTarget {
        var flattened = 0
        var halted: String? = null
        var resumed = 0

        override fun flatten() {
            flattened++
        }

        override fun halt(reason: String) {
            halted = reason
        }

        override fun resume() {
            resumed++
        }
    }

    private fun bookRiskState(
        pnl: FakePnL,
        clock: Clock,
    ): RiskState =
        RiskState(
            pnl,
            StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            BigDecimal("100000"),
            DailyDrawdownBasis.BALANCE,
        )

    @Test
    fun `flattens and halts every child on a static total breach, once`() {
        val clock = TestClock(0L)
        val rs = bookRiskState(FakePnL(BigDecimal("-9000"), BigDecimal.ZERO), clock) // 9% of 100000 > 8%
        val a = FakeChild()
        val b = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                children = listOf(a, b),
                bookRiskState = rs,
                haltRules = listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
                clock = clock,
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
        val clock = TestClock(0L)
        val pnl = FakePnL(BigDecimal.ZERO, BigDecimal.ZERO)
        val rs = bookRiskState(pnl, clock)
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(listOf(a), rs, listOf(MaxDailyDrawdown(BigDecimal("0.04"))), clock)

        agg.evaluate() // captures day-start ref at 100000 (Continue)
        assertThat(a.halted).isNull()
        pnl.realized = BigDecimal("-5000") // 5% intraday > 4%
        agg.evaluate()

        assertThat(a.flattened).isEqualTo(1)
        assertThat(a.halted).isNotNull()
    }

    @Test
    fun `daily breach auto-resumes children at the next UTC midnight`() {
        val clock = TestClock(0L)
        val pnl = FakePnL(BigDecimal.ZERO, BigDecimal.ZERO)
        val rs = bookRiskState(pnl, clock)
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(listOf(a), rs, listOf(MaxDailyDrawdown(BigDecimal("0.04"))), clock)

        agg.evaluate() // captures day-start ref at 100000 (Continue)
        pnl.realized = BigDecimal("-5000") // 5% intraday > 4%
        agg.evaluate() // breach: flatten + halt, latched DAILY
        assertThat(a.flattened).isEqualTo(1)
        assertThat(a.halted).isNotNull()
        assertThat(a.resumed).isEqualTo(0)

        clock.t = DAY_MS // roll into the next UTC day; tracker re-captures ref at 95000 → DD 0
        agg.evaluate()

        assertThat(a.resumed).isEqualTo(1)
        assertThat(a.flattened).isEqualTo(1) // not re-tripped — DD reset on the new day
    }

    @Test
    fun `total breach stays latched across a UTC midnight`() {
        val clock = TestClock(0L)
        val rs = bookRiskState(FakePnL(BigDecimal("-9000"), BigDecimal.ZERO), clock) // 9% > 8%
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                listOf(a),
                rs,
                listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
                clock,
            )

        agg.evaluate() // PERSISTENT breach
        assertThat(a.flattened).isEqualTo(1)

        clock.t = DAY_MS // a new UTC day must NOT clear a total-drawdown halt
        agg.evaluate()

        assertThat(a.resumed).isEqualTo(0)
        assertThat(a.flattened).isEqualTo(1)
    }

    @Test
    fun `does nothing while under the limit`() {
        val clock = TestClock(0L)
        val rs = bookRiskState(FakePnL(BigDecimal("-1000"), BigDecimal.ZERO), clock) // 1% < 8%
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                listOf(a),
                rs,
                listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
                clock,
            )
        agg.evaluate()
        assertThat(a.flattened).isEqualTo(0)
        assertThat(a.halted).isNull()
    }
}
