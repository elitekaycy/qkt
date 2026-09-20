package com.qkt.cli.daemon.portfolio

import com.qkt.persistence.PersistedRiskState
import com.qkt.risk.FakePnL
import com.qkt.risk.HaltScope
import com.qkt.risk.TestClock
import com.qkt.risk.rules.MaxDailyDrawdown
import com.qkt.risk.rules.MaxDailyLoss
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioRiskAggregatorResumeTest : PortfolioRiskAggregatorFixture() {
    @Test
    fun `persisted book halt is re-applied to children after restart`() {
        val clock = TestClock(0L)
        var persisted: PersistedRiskState? = null
        val firstState =
            bookRiskState(FakePnL(BigDecimal("-1100"), BigDecimal.ZERO), clock) { state -> persisted = state }
        val firstChild = FakeChild()
        val first =
            PortfolioRiskAggregator(
                listOf(firstChild),
                firstState,
                listOf(MaxDailyLoss(BigDecimal("1000"))),
                clock,
            )
        first.recordRealized("alpha", BigDecimal("-1100"))
        first.evaluate()

        val restoredState = bookRiskState(FakePnL(BigDecimal("-1100"), BigDecimal.ZERO), clock)
        restoredState.restore(requireNotNull(persisted))
        val restoredChild = FakeChild()
        val restored =
            PortfolioRiskAggregator(
                listOf(restoredChild),
                restoredState,
                listOf(MaxDailyLoss(BigDecimal("1000"))),
                clock,
            )

        restored.evaluate()

        assertThat(restoredState.halted).isTrue
        assertThat(restoredState.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("-1100")
        assertThat(restoredChild.flattened).isEqualTo(1)
        assertThat(restoredChild.halted).contains("daily loss")
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
        assertThat(a.scope).isEqualTo(HaltScope.DAILY)
        assertThat(a.resumed).isEqualTo(0)

        clock.t = DAY_MS // roll into the next UTC day; tracker re-captures ref at 95000 → DD 0
        agg.evaluate()

        assertThat(a.resumed).isEqualTo(1)
        assertThat(a.flattened).isEqualTo(1) // not re-tripped — DD reset on the new day
    }

    @Test
    fun `daily expiry does not resume a child with an unrelated persistent halt`() {
        val clock = TestClock(0L)
        val pnl = FakePnL(BigDecimal.ZERO, BigDecimal.ZERO)
        val state = bookRiskState(pnl, clock)
        val bookOwned = FakeChild()
        val engineFaulted = FakeChild().also { it.halt("engine fault", HaltScope.PERSISTENT) }
        val aggregator =
            PortfolioRiskAggregator(
                listOf(bookOwned, engineFaulted),
                state,
                listOf(MaxDailyDrawdown(BigDecimal("0.04"))),
                clock,
            )

        aggregator.evaluate()
        pnl.realized = BigDecimal("-5000")
        aggregator.evaluate()
        clock.t = DAY_MS
        aggregator.evaluate()

        assertThat(bookOwned.resumed).isEqualTo(1)
        assertThat(engineFaulted.resumed).isZero()
        assertThat(engineFaulted.halted).isEqualTo("engine fault")
        assertThat(engineFaulted.scope).isEqualTo(HaltScope.PERSISTENT)
    }
}
