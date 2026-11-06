package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.trackerFill
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskTrackerEdgesTest {
    private val clock = FixedClock(0L)
    private val positions = StrategyPositionTracker()
    private val prices = MarketPriceTracker()
    private val strategyPnL = StrategyPnL(positions, prices)

    @Test
    fun `daily drawdown without cached equity computes it from pnl`() {
        val pnl = FakePnL()
        strategyPnL.setStartingBalance("s", BigDecimal("1000"))
        val tracker = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal("1000"), pnl, strategyPnL)
        tracker.globalDrawdownToday()
        tracker.strategyDrawdownToday("s")

        pnl.unrealized = BigDecimal("-100")
        strategyPnL.recordRealized("s", BigDecimal("-50"))

        assertThat(tracker.globalDrawdownToday()).isEqualByComparingTo("0.1")
        assertThat(tracker.strategyDrawdownToday("s")).isEqualByComparingTo("0.05")
    }

    @Test
    fun `daily drawdown from a non-positive reference is zero`() {
        val tracker = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal.ZERO, FakePnL(), strategyPnL)

        assertThat(tracker.globalDrawdownToday()).isEqualByComparingTo("0")
        assertThat(tracker.strategyDrawdownToday("nobody")).isEqualByComparingTo("0")
    }

    @Test
    fun `drawdown is zero at a new high, without a peak, or against no balance`() {
        val pnl = FakePnL()
        val equity = EquityTracker(pnl, strategyPnL, BigDecimal("1000"))
        val drawdown = DrawdownTracker(equity)
        pnl.realized = BigDecimal("10")
        equity.update()

        assertThat(drawdown.globalDrawdown()).isEqualByComparingTo("0")
        assertThat(drawdown.strategyDrawdown("unfunded")).isEqualByComparingTo("0")
        assertThat(drawdown.strategyStaticDrawdown("unfunded", BigDecimal.ZERO)).isEqualByComparingTo("0")
    }

    @Test
    fun `a blank strategy is never tracked and an unsampled peak reads current equity`() {
        strategyPnL.setStartingBalance("s", BigDecimal("500"))
        val equity = EquityTracker(FakePnL(), strategyPnL)

        assertThat(equity.updateStrategy("")).isFalse()
        assertThat(equity.peakEquityFor("s")).isEqualByComparingTo("500")
    }

    @Test
    fun `an order on a flat or net-zero symbol is not risk-reducing`() {
        val hedged = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("s", "l", "long")
        intents.independentOpen("s", "h", "short")
        intents.apply(hedged, trackerFill("s", "l", "X", Side.BUY, "1", "100"))
        intents.apply(hedged, trackerFill("s", "h", "X", Side.SELL, "1", "100"))
        val sell = OrderRequest.Market("o", "X", Side.SELL, BigDecimal.ONE, TimeInForce.GTC, 0L, "s")

        assertThat(isRiskReducing(sell, StrategyPositionTracker().account)).isFalse()
        assertThat(isRiskReducing(sell, hedged.account)).isFalse()
    }

    @Test
    fun `a disabled counter or a blank strategy never trips the runaway breaker`() {
        val state = RiskState.noOp(clock)
        val off = RunawayBreaker(clock, state, maxRoundTrips = 0, maxRejections = 0)
        val on = RunawayBreaker(clock, state, maxRoundTrips = 1, maxRejections = 1)

        repeat(3) {
            off.recordClose("s")
            off.recordRejection("s")
            on.recordClose("")
            on.recordRejection("")
        }

        assertThat(state.strategyHalted("s")).isFalse()
        assertThat(state.strategyHalts()).isEmpty()
    }
}
