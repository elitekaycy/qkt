package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PnLCalculatorTest {
    private val ledger = StrategyPositionTracker()
    private val tracker = ledger.account
    private val priceTracker = MarketPriceTracker()
    private val pnl = PnLCalculator(tracker, priceTracker)

    @Test
    fun `realizedTotal is zero on a fresh calculator`() {
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `recordRealized accumulates positive realized values`() {
        pnl.recordRealized(Money.of("10"))
        pnl.recordRealized(Money.of("25.5"))
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("35.5"))
    }

    @Test
    fun `recordRealized accumulates negative realized values (losses)`() {
        pnl.recordRealized(Money.of("10"))
        pnl.recordRealized(Money.of("-15"))
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("-5"))
    }

    @Test
    fun `unrealizedFor returns zero for unknown symbol`() {
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unrealizedFor returns zero when no current price for symbol`() {
        ledger.apply(
            "acct",
            Trade("ORD-X", "XAUUSD", Money.of("100"), Money.of("1"), Side.BUY, 1000L),
        )
        // priceTracker has no price for XAUUSD
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unrealizedFor computes (price - avg) * quantity for a long position`() {
        ledger.apply(
            "acct",
            Trade("ORD-X", "XAUUSD", Money.of("100"), Money.of("2"), Side.BUY, 1000L),
        )
        priceTracker.update("XAUUSD", Money.of("110"))
        // (110 - 100) * 2 = 20
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `unrealizedFor returns negative for a short position with rising price`() {
        ledger.apply(
            "acct",
            Trade("ORD-X", "XAUUSD", Money.of("100"), Money.of("2"), Side.SELL, 1000L),
        )
        priceTracker.update("XAUUSD", Money.of("110"))
        // (110 - 100) * -2 = -20
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("-20"))
    }

    @Test
    fun `unrealizedTotal sums across all open symbols`() {
        ledger.apply(
            "acct",
            Trade("ORD-1", "XAUUSD", Money.of("100"), Money.of("2"), Side.BUY, 1000L),
        )
        ledger.apply(
            "acct",
            Trade("ORD-2", "EURUSD", Money.of("1.10"), Money.of("100"), Side.BUY, 1000L),
        )
        priceTracker.update("XAUUSD", Money.of("110"))
        priceTracker.update("EURUSD", Money.of("1.20"))
        // XAUUSD: (110-100) * 2 = 20
        // EURUSD: (1.20-1.10) * 100 = 10
        assertThat(pnl.unrealizedTotal()).isEqualByComparingTo(Money.of("30"))
    }
}
