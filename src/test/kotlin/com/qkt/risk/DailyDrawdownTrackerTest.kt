package com.qkt.risk

import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyDrawdownTrackerTest {
    private fun strategyPnL() = StrategyPnL(StrategyPositionTracker(), MarketPriceTracker())

    @Test
    fun `balance basis excludes open float from the day-start reference`() {
        val clock = TestClock(0L)
        // Day-start float of +100 must NOT raise the balance reference; ref stays 10000.
        val pnl = FakePnL(BigDecimal("0"), BigDecimal("100"))
        val t = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal("10000"), pnl, strategyPnL())
        t.globalDrawdownToday() // captures ref = 10000
        // Equity now drops 400 below the balance reference.
        pnl.realized = BigDecimal("-400")
        pnl.unrealized = BigDecimal("0")
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal("0.04"))
    }

    @Test
    fun `equity basis includes open float in the reference`() {
        val clock = TestClock(0L)
        val pnl = FakePnL(BigDecimal("0"), BigDecimal("100"))
        val t = DailyDrawdownTracker(clock, DailyDrawdownBasis.EQUITY, BigDecimal("10000"), pnl, strategyPnL())
        t.globalDrawdownToday() // ref = 10100 (includes the +100 float)
        pnl.unrealized = BigDecimal("0") // equity back to 10000 => 100/10100 below the equity ref
        assertThat(t.globalDrawdownToday()).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `rolls over at UTC midnight and recaptures the reference`() {
        val clock = TestClock(0L)
        val pnl = FakePnL(BigDecimal("0"), BigDecimal("0"))
        val t = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal("10000"), pnl, strategyPnL())
        t.globalDrawdownToday() // captures ref = 10000 at day start (balance, no loss yet)
        pnl.realized = BigDecimal("-400") // lose 400 intraday
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal("0.04")) // ref 10000, equity 9600
        clock.t = 86_400_000L // next UTC day → reference recaptured at the current balance 9600
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `zero drawdown when in profit`() {
        val clock = TestClock(0L)
        val pnl = FakePnL(BigDecimal("500"), BigDecimal("0"))
        val t = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal("10000"), pnl, strategyPnL())
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
