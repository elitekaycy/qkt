package com.qkt.candles

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleWindowRollTest : CandleAggregatorFixture() {
    @Test
    fun `first tick for a symbol does not emit a CandleEvent`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `tick within current window updates OHLC in place without emitting`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("XAUUSD", Money.of("2401.5"), 30_000L)
        publishTick("XAUUSD", Money.of("2399.5"), 45_000L)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `tick past current endTime emits CandleEvent for the closed window`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 30_000L)
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.symbol).isEqualTo("XAUUSD")
    }

    @Test
    fun `boundary timestamp triggers window roll`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 30_000L)
        publishTick("XAUUSD", Money.of("2401.0"), 60_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.startTime).isEqualTo(0L)
        assertThat(captured[0].candle.endTime).isEqualTo(60_000L)
    }

    @Test
    fun `multiple consecutive rolls each emit one CandleEvent`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("XAUUSD", Money.of("2401.0"), 60_000L)
        publishTick("XAUUSD", Money.of("2402.0"), 120_000L)
        publishTick("XAUUSD", Money.of("2403.0"), 180_000L)
        assertThat(captured).hasSize(3)
        assertThat(captured.map { it.candle.startTime }).containsExactly(0L, 60_000L, 120_000L)
    }
}
