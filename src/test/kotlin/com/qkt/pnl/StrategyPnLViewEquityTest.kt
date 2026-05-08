package com.qkt.pnl

import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPnLViewEquityTest {
    @Test
    fun `equity is startingBalance plus total when no positions`() {
        val pnl = StrategyPnL(StrategyPositionTracker(), MarketPriceTracker())
        pnl.setStartingBalance("s", BigDecimal("10000"))
        val view = StrategyPnLViewImpl(pnl, "s")
        assertThat(view.equity()).isEqualByComparingTo("10000")
        assertThat(view.balance()).isEqualByComparingTo("10000")
    }

    @Test
    fun `balance reflects realized PnL`() {
        val pnl = StrategyPnL(StrategyPositionTracker(), MarketPriceTracker())
        pnl.setStartingBalance("s", BigDecimal("10000"))
        pnl.recordRealized("s", BigDecimal("250"))
        val view = StrategyPnLViewImpl(pnl, "s")
        assertThat(view.balance()).isEqualByComparingTo("10250")
    }

    @Test
    fun `default starting balance is zero`() {
        val pnl = StrategyPnL(StrategyPositionTracker(), MarketPriceTracker())
        val view = StrategyPnLViewImpl(pnl, "unset")
        assertThat(view.equity()).isEqualByComparingTo("0")
        assertThat(view.balance()).isEqualByComparingTo("0")
    }
}
