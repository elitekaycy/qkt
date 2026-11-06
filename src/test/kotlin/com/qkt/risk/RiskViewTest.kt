package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.trackerFill
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskViewTest {
    private val clock = FixedClock(0L)
    private val prices = MarketPriceTracker()
    private val positions = StrategyPositionTracker()
    private val strategyPnL = StrategyPnL(positions, prices)
    private val state =
        RiskState(
            PnLCalculator(positions.account, prices),
            strategyPnL,
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            initialBalance = BigDecimal("1000"),
        )
    private val view = RiskViewImpl(state, "A")

    @Test
    fun `a strategy view reads its own halt and the account halt from the risk state`() {
        assertThat(view.halted).isFalse()
        assertThat(view.haltReason).isNull()
        assertThat(view.globalHalted).isFalse()

        state.haltStrategy("A", "daily loss")
        assertThat(view.halted).isTrue()
        assertThat(view.haltReason).isEqualTo("daily loss")
        assertThat(view.globalHalted).isFalse()

        state.halt("account breach")
        assertThat(view.globalHalted).isTrue()
        assertThat(view.haltReason).isEqualTo("account breach")
    }

    @Test
    fun `a strategy view samples equity peak and drawdowns live`() {
        strategyPnL.setStartingBalance("A", BigDecimal("1000"))
        state.equityTracker.update()
        state.equityTracker.updateStrategy("A")

        IntentBook().apply(positions, trackerFill("A", "c1", "X", Side.BUY, "1", "100"))
        prices.update("X", Money.of("90"))
        state.equityTracker.update()
        state.equityTracker.updateStrategy("A")

        assertThat(view.currentEquity).isEqualByComparingTo("990")
        assertThat(view.equityPeak).isEqualByComparingTo("1000")
        assertThat(view.drawdown).isEqualByComparingTo("0.01")
        assertThat(view.globalDrawdown).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a strategy view reports realized pnl for the day and the month`() {
        state.dailyPnLTracker.recordRealized("A", BigDecimal("5"))
        state.monthlyPnLTracker.recordRealized("A", BigDecimal("7"))
        state.dailyPnLTracker.recordRealized("B", BigDecimal("100"))

        assertThat(view.realizedToday).isEqualByComparingTo("5")
        assertThat(view.realizedMonth).isEqualByComparingTo("7")
    }

    @Test
    fun `the inert view reports nothing halted and zero figures`() {
        val inert = NoOpRiskView()

        assertThat(inert.halted).isFalse()
        assertThat(inert.haltReason).isNull()
        assertThat(inert.globalHalted).isFalse()
        assertThat(
            listOf(
                inert.currentEquity,
                inert.equityPeak,
                inert.drawdown,
                inert.realizedToday,
                inert.realizedMonth,
                inert.globalDrawdown,
            ),
        ).allSatisfy { assertThat(it).isEqualByComparingTo("0") }
    }
}
