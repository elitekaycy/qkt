package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.NoopStatePersistor
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Halt flags and the day's realized PnL survive a restart: a strategy that halted on
 * its daily loss must come back halted with its budget still spent — not un-halted
 * with a fresh budget the same day it exhausted one.
 */
class RiskStatePersistenceTest {
    private data class Fixture(
        val state: RiskState,
        val pnl: PnLCalculator,
    )

    private fun fixture(
        clock: TestClock,
        persistor: NoopStatePersistor,
        initialBalance: BigDecimal = BigDecimal("10000"),
    ): Fixture {
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        strategyPnL.setStartingBalance("s1", initialBalance)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        return Fixture(
            RiskState(
                pnl,
                strategyPnL,
                clock,
                bus,
                initialBalance = initialBalance,
                persist = { snap -> persistor.saveRiskState("s1", snap) },
            ),
            pnl,
        )
    }

    private fun riskState(
        clock: TestClock,
        persistor: NoopStatePersistor,
    ): RiskState = fixture(clock, persistor, BigDecimal.ZERO).state

    @Test
    fun `halt and daily pnl survive a restart`() {
        val clock = TestClock(86_400_000L * 100 + 3_600_000L) // day 100, 01:00 UTC
        val persistor = NoopStatePersistor()
        val first = riskState(clock, persistor)
        first.onFill("s1", BigDecimal("-900"))
        first.halt("daily loss 900 exceeds max 800", scope = HaltScope.DAILY)

        // "Restart": a fresh RiskState restoring the persisted snapshot, same day.
        val second = riskState(clock, persistor)
        second.restore(persistor.loadRiskState("s1")!!)
        assertThat(second.halted).isTrue()
        assertThat(second.haltReason).contains("daily loss")
        assertThat(second.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("-900")
        // The auto-resume sweep must NOT clear a same-day daily halt.
        second.clearExpiredDailyHalts()
        assertThat(second.halted).isTrue()
    }

    @Test
    fun `a daily halt from yesterday legitimately clears on restore`() {
        val clock = TestClock(86_400_000L * 100 + 3_600_000L)
        val persistor = NoopStatePersistor()
        val first = riskState(clock, persistor)
        first.onFill("s1", BigDecimal("-900"))
        first.halt("daily loss", scope = HaltScope.DAILY)

        // Restart the next UTC day: the daily halt expires, the budget is fresh.
        clock.t += 86_400_000L
        val second = riskState(clock, persistor)
        second.restore(persistor.loadRiskState("s1")!!)
        assertThat(second.halted).isFalse()
        assertThat(second.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("0")
    }

    @Test
    fun `a persistent halt survives across days`() {
        val clock = TestClock(86_400_000L * 100)
        val persistor = NoopStatePersistor()
        val first = riskState(clock, persistor)
        first.haltStrategy("s1", "trailing drawdown breached", scope = HaltScope.PERSISTENT)

        clock.t += 86_400_000L * 3
        val second = riskState(clock, persistor)
        second.restore(persistor.loadRiskState("s1")!!)
        assertThat(second.isStrategyHalted("s1")).isTrue()
        assertThat(second.haltReasonFor("s1")).contains("trailing drawdown")
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
