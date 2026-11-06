package com.qkt.risk.rules

import com.qkt.broker.FakeBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DaemonRiskScope
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Every limit refuses a non-positive size, and every strategy-scoped limit a blank strategy. */
class RiskRuleConstructionTest {
    private val zero = BigDecimal.ZERO
    private val ledger = StrategyPositionTracker()

    @Test
    fun `order and position limits refuse a non-positive size`() {
        assertThatThrownBy { MaxOrderQty(zero) }.hasMessage("maxQty must be > 0: 0")
        assertThatThrownBy { MaxPositionSize("X", zero) }.hasMessage("maxQty must be > 0: 0")
        assertThatThrownBy { MaxOrderNotional(zero, MarketPriceTracker()) }.hasMessage("maxNotional must be > 0: 0")
        assertThatThrownBy { PriceCollar(zero, MarketPriceTracker()) }.hasMessage("maxDeviationFrac must be > 0: 0")
        assertThatThrownBy { MaxStrategyPositionSize("s", zero, ledger) }.hasMessage("maxQty must be > 0: 0")
        assertThatThrownBy { MaxStrategyOpenPositions("s", 0, ledger) }.hasMessage("maxCount must be > 0: 0")
    }

    @Test
    fun `loss limits refuse a non-positive loss`() {
        assertThatThrownBy { MaxDailyLoss(zero) }.hasMessage("maxLoss must be > 0: 0")
        assertThatThrownBy { MaxStrategyDailyLoss("s", zero) }.hasMessage("maxLoss must be > 0: 0")
        assertThatThrownBy { DaemonDailyLoss("d", zero, DaemonRiskScope(FixedClock(0L))) }
            .hasMessage("maxLoss must be > 0: 0")
    }

    @Test
    fun `strategy-scoped limits refuse a blank strategy`() {
        val one = BigDecimal.ONE
        val half = BigDecimal("0.5")
        val blanks =
            listOf(
                { MaxStrategyPositionSize(" ", one, ledger) },
                { MaxStrategyOpenPositions("", 1, ledger) },
                { MaxStrategyDailyLoss("", one) },
                { MaxStrategyDrawdown("", half) },
                { MaxStrategyDailyDrawdown("", half) },
            )

        for (build in blanks) assertThatThrownBy { build() }.hasMessage("strategyId must be non-blank")
    }

    @Test
    fun `drawdown limits refuse fractions on both sides of the range`() {
        assertThatThrownBy { MaxDrawdown(zero) }.hasMessage("maxFraction must be in (0, 1]: 0")
        assertThatThrownBy { MaxDailyDrawdown(BigDecimal("2")) }.hasMessage("maxFraction must be in (0, 1]: 2")
        assertThatThrownBy { MaxStrategyDrawdown("s", zero) }.hasMessage("maxFraction must be in (0, 1]: 0")
        assertThatThrownBy { MaxStrategyDailyDrawdown("s", BigDecimal("2")) }
            .hasMessage("maxFraction must be in (0, 1]: 2")
    }

    @Test
    fun `the margin floor refuses a non-positive floor`() {
        val clock = FixedClock(0L)
        val broker = FakeBroker(EventBus(clock, MonotonicSequenceGenerator()), clock, emptySet())

        assertThatThrownBy { MarginFloor(broker, zero) }.hasMessage("floorPct must be > 0: 0")
    }
}
