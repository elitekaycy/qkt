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
    private fun riskState(
        clock: TestClock,
        persistor: NoopStatePersistor,
    ): RiskState {
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        return RiskState(
            pnl,
            strategyPnL,
            clock,
            bus,
            persist = { snap -> persistor.saveRiskState("s1", snap) },
        )
    }

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
}
