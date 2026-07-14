package com.qkt.marketdata

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketPriceTrackerTest {
    @Test
    fun `lastPrice returns null for unknown symbol`() {
        val tracker = MarketPriceTracker()
        assertThat(tracker.lastPrice("XAUUSD")).isNull()
    }

    @Test
    fun `update then lastPrice returns the value`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("2400.5"))
        assertThat(tracker.lastPrice("XAUUSD")).isEqualByComparingTo(Money.of("2400.5"))
    }

    @Test
    fun `update overwrites previous value for same symbol`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("2400.0"))
        tracker.update("XAUUSD", Money.of("2401.5"))
        assertThat(tracker.lastPrice("XAUUSD")).isEqualByComparingTo(Money.of("2401.5"))
    }

    @Test
    fun `tracks multiple symbols independently`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("2400.0"))
        tracker.update("EURUSD", Money.of("1.0921"))
        assertThat(tracker.lastPrice("XAUUSD")).isEqualByComparingTo(Money.of("2400.0"))
        assertThat(tracker.lastPrice("EURUSD")).isEqualByComparingTo(Money.of("1.0921"))
    }

    @Test
    fun `tick update retains side-aware execution prices`() {
        val tracker = MarketPriceTracker()

        tracker.update(
            Tick(
                symbol = "XAUUSD",
                price = Money.of("2400.0"),
                timestamp = 1L,
                bid = Money.of("2399.5"),
                ask = Money.of("2400.5"),
            ),
        )

        assertThat(tracker.executionPrice("XAUUSD", Side.BUY)).isEqualByComparingTo("2400.5")
        assertThat(tracker.executionPrice("XAUUSD", Side.SELL)).isEqualByComparingTo("2399.5")
        assertThat(tracker.lastPrice("XAUUSD")).isEqualByComparingTo("2400.0")
    }

    @Test
    fun `single-price update is the execution-price fallback for both sides`() {
        val tracker = MarketPriceTracker()

        tracker.update("XAUUSD", Money.of("2400.0"))

        assertThat(tracker.executionPrice("XAUUSD", Side.BUY)).isEqualByComparingTo("2400.0")
        assertThat(tracker.executionPrice("XAUUSD", Side.SELL)).isEqualByComparingTo("2400.0")
    }
}
