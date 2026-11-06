package com.qkt.pnl

import com.qkt.common.Side
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.trackerFill
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPnLViewReadsTest {
    private val prices = MarketPriceTracker()
    private val positions = StrategyPositionTracker()
    private val pnl = StrategyPnL(positions, prices)

    @Test
    fun `the view reads one strategy's open and total pnl per symbol`() {
        IntentBook().apply(positions, trackerFill("s", "c1", "X", Side.BUY, "2", "100"))
        prices.update("X", BigDecimal("103"))
        pnl.recordRealized("s", BigDecimal("4"))
        val view = StrategyPnLViewImpl(pnl, "s")

        assertThat(view.unrealizedFor("X")).isEqualByComparingTo("6")
        assertThat(view.unrealizedTotal()).isEqualByComparingTo("6")
        assertThat(view.realized()).isEqualByComparingTo("4")
        assertThat(view.total()).isEqualByComparingTo("10")
    }

    @Test
    fun `a priced symbol the strategy does not hold has no open pnl`() {
        prices.update("X", BigDecimal("103"))
        IntentBook().apply(positions, trackerFill("other", "c1", "X", Side.BUY, "2", "100"))

        assertThat(pnl.unrealizedFor("s", "X")).isEqualByComparingTo("0")
        assertThat(StrategyPnLViewImpl(pnl, "s").unrealizedFor("X")).isEqualByComparingTo("0")
    }
}
