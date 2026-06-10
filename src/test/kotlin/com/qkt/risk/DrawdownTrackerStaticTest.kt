package com.qkt.risk

import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownTrackerStaticTest {
    private fun tracker(
        realized: String,
        unrealized: String,
        startingBalance: String = "10000",
    ): DrawdownTracker {
        // Anchored at the same balance the rule measures against — the production wiring
        // (RiskState seeds the tracker with the configured initialBalance).
        val et =
            EquityTracker(
                FakePnL(BigDecimal(realized), BigDecimal(unrealized)),
                StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
                BigDecimal(startingBalance),
            )
        et.update()
        return DrawdownTracker(et)
    }

    @Test
    fun `static global drawdown is loss over initial balance`() {
        // PnL -800 on a 10000 account => 8% static drawdown.
        assertThat(tracker("-800", "0").globalStaticDrawdown(BigDecimal("10000")))
            .isEqualByComparingTo(BigDecimal("0.08"))
    }

    @Test
    fun `static drawdown counts open float`() {
        // realized -300, unrealized -500 => -800 total => 8%.
        assertThat(tracker("-300", "-500").globalStaticDrawdown(BigDecimal("10000")))
            .isEqualByComparingTo(BigDecimal("0.08"))
    }

    @Test
    fun `static drawdown is zero when in profit`() {
        assertThat(tracker("500", "0").globalStaticDrawdown(BigDecimal("10000")))
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `static drawdown is zero when initial balance is non-positive`() {
        assertThat(tracker("-800", "0", startingBalance = "0").globalStaticDrawdown(BigDecimal.ZERO))
            .isEqualByComparingTo(BigDecimal.ZERO)
    }
}
