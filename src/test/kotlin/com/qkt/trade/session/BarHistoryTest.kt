package com.qkt.trade.session

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarHistoryTest {
    private fun candle(
        symbol: String,
        start: Long,
    ) = Candle(
        symbol = symbol,
        open = BigDecimal("1"),
        high = BigDecimal("2"),
        low = BigDecimal("0.5"),
        close = BigDecimal("1.5"),
        volume = BigDecimal.ONE,
        startTime = start,
        endTime = start + 60_000L,
    )

    @Test
    fun `retains only the newest capacity bars per symbol`() {
        val history = BarHistory(capacity = 3)
        (1L..5L).forEach { history.record(candle("XAUUSD", it * 60_000L)) }
        val last = history.last("XAUUSD", 10)
        assertThat(last).hasSize(3)
        assertThat(last.map { it.startTime }).containsExactly(180_000L, 240_000L, 300_000L)
    }

    @Test
    fun `last returns newest count bars newest-last`() {
        val history = BarHistory(capacity = 10)
        (1L..4L).forEach { history.record(candle("XAUUSD", it * 60_000L)) }
        assertThat(history.last("XAUUSD", 2).map { it.startTime }).containsExactly(180_000L, 240_000L)
    }

    @Test
    fun `countFor counts every recorded bar beyond capacity`() {
        val history = BarHistory(capacity = 2)
        (1L..5L).forEach { history.record(candle("EURUSD", it * 60_000L)) }
        assertThat(history.countFor("EURUSD")).isEqualTo(5L)
    }

    @Test
    fun `seed pre-loads bars and record continues the count`() {
        val history = BarHistory(capacity = 5)
        history.seed("XAUUSD", (1L..3L).map { candle("XAUUSD", it * 60_000L) })
        history.record(candle("XAUUSD", 240_000L))
        assertThat(history.countFor("XAUUSD")).isEqualTo(4L)
        assertThat(history.last("XAUUSD", 4).map { it.startTime })
            .containsExactly(60_000L, 120_000L, 180_000L, 240_000L)
    }

    @Test
    fun `unknown symbol yields empty history and zero count`() {
        val history = BarHistory(capacity = 3)
        assertThat(history.last("GBPUSD", 5)).isEmpty()
        assertThat(history.countFor("GBPUSD")).isEqualTo(0L)
    }
}
