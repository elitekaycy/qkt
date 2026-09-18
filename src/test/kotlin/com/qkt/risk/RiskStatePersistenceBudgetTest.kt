package com.qkt.risk

import com.qkt.persistence.NoopStatePersistor
import com.qkt.risk.RiskStatePersistenceFixtures.fixture
import com.qkt.risk.RiskStatePersistenceFixtures.riskState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Halt flags and the day's realized PnL survive a restart: a strategy that halted on
 * its daily loss must come back halted with its budget still spent — not un-halted
 * with a fresh budget the same day it exhausted one.
 */
class RiskStatePersistenceBudgetTest {
    @Test
    fun `month-to-date realized pnl survives a restart within the month, not into the next (#855)`() {
        val clock =
            TestClock(
                java.time.Instant
                    .parse("2024-03-10T12:00:00Z")
                    .toEpochMilli(),
            )
        val persistor = NoopStatePersistor()
        val first = riskState(clock, persistor)
        first.onFill("s1", BigDecimal("-120"))
        clock.t =
            java.time.Instant
                .parse("2024-03-11T12:00:00Z")
                .toEpochMilli()
        first.onFill("s1", BigDecimal("-30"))

        val restarted = riskState(clock, persistor)
        restarted.restore(persistor.loadRiskState("s1")!!)
        assertThat(RiskViewImpl(restarted, "s1").realizedMonth).isEqualByComparingTo("-150")
        // The day tracker only kept today's fill.
        assertThat(RiskViewImpl(restarted, "s1").realizedToday).isEqualByComparingTo("-30")

        clock.t =
            java.time.Instant
                .parse("2024-04-01T00:00:01Z")
                .toEpochMilli()
        val nextMonth = riskState(clock, persistor)
        nextMonth.restore(persistor.loadRiskState("s1")!!)
        assertThat(RiskViewImpl(nextMonth, "s1").realizedMonth).isEqualByComparingTo("0")
    }

    @Test
    fun `same-day restart preserves the spent percent drawdown budget`() {
        val clock = TestClock(86_400_000L * 100 + 3_600_000L)
        val persistor = NoopStatePersistor()
        val first = fixture(clock, persistor)
        first.state.initializeAnchors(listOf("s1"))
        first.pnl.recordRealized(BigDecimal("-300"))
        first.state.onFill("s1", BigDecimal("-300"))

        val second = fixture(clock, persistor)
        second.state.restore(persistor.loadRiskState("s1")!!)
        second.state.initializeAnchors(listOf("s1"))

        assertThat(second.pnl.realizedTotal()).isEqualByComparingTo("-300")
        assertThat(second.state.dailyDrawdownTracker.globalDrawdownToday()).isEqualByComparingTo("0.03")
    }

    @Test
    fun `restart preserves the trailing equity high-water mark`() {
        val clock = TestClock(86_400_000L * 100 + 3_600_000L)
        val persistor = NoopStatePersistor()
        val first = fixture(clock, persistor)
        first.state.initializeAnchors(listOf("s1"))
        first.pnl.recordRealized(BigDecimal("1000"))
        first.state.onTick()
        first.state.persistAnchorsIfDirty()
        first.pnl.recordRealized(BigDecimal("-800"))
        first.state.onFill("s1", BigDecimal("-800"))

        val second = fixture(clock, persistor)
        second.state.restore(persistor.loadRiskState("s1")!!)
        second.state.initializeAnchors(listOf("s1"))

        assertThat(second.state.equityTracker.peakEquity()).isEqualByComparingTo("11000")
        assertThat(second.state.drawdownTracker.globalDrawdown()).isEqualByComparingTo("0.07272727")
    }

    @Test
    fun `restart preserves daily trade count loss streak and cooldown`() {
        val now = 86_400_000L * 100 + 3_600_000L
        val clock = TestClock(now)
        val persistor = NoopStatePersistor()
        val first = fixture(clock, persistor)
        first.state.initializeAnchors(listOf("s1"))
        first.state.pacerLedger.recordEntryFill("s1", now - 2_000L)
        first.state.pacerLedger.recordEntryFill("s1", now - 1_000L)
        first.state.pacerLedger.recordOutcome("s1", now - 500L, BigDecimal("-1"))
        first.state.pacerLedger.recordOutcome("s1", now - 100L, BigDecimal("-1"))
        first.state.onFill("s1", BigDecimal.ZERO)

        val second = fixture(clock, persistor)
        second.state.restore(persistor.loadRiskState("s1")!!)

        assertThat(second.state.pacerLedger.tradesToday("s1", now)).isEqualTo(2)
        assertThat(second.state.pacerLedger.lossStreak("s1")).isEqualTo(2)
        assertThat(second.state.pacerLedger.cooldownRemainingMs("s1", now, 10_000L, 2)).isEqualTo(9_900L)
    }

    @Test
    fun `first fill after midnight cannot re-anchor the daily drawdown after its loss`() {
        val clock = TestClock(86_400_000L * 100 + 23 * 3_600_000L)
        val persistor = NoopStatePersistor()
        val fixture = fixture(clock, persistor)
        fixture.state.initializeAnchors(listOf("s1"))
        clock.t += 2 * 3_600_000L

        fixture.state.beforeFill("s1")
        fixture.pnl.recordRealized(BigDecimal("-500"))
        fixture.state.onFill("s1", BigDecimal("-500"))

        assertThat(fixture.state.dailyDrawdownTracker.globalDrawdownToday()).isEqualByComparingTo("0.05")
    }
}
