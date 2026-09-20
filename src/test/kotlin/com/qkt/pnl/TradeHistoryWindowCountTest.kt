package com.qkt.pnl

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradeHistoryWindowCountTest {
    @Test
    fun `tradesSince counts only entries at or after the cutoff`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X")
        h.recordTrade("s", 200L, BigDecimal("-5"), "X")
        h.recordTrade("s", 300L, BigDecimal("20"), "X")
        assertThat(h.tradesSince("s", 0L)).isEqualTo(3)
        assertThat(h.tradesSince("s", 200L)).isEqualTo(2)
        assertThat(h.tradesSince("s", 250L)).isEqualTo(1)
        assertThat(h.tradesSince("s", 400L)).isZero
    }

    @Test
    fun `winsSince and lossesSince filter by outcome`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X") // W
        h.recordTrade("s", 200L, BigDecimal("-5"), "X") // L
        h.recordTrade("s", 300L, BigDecimal("20"), "X") // W
        h.recordTrade("s", 400L, BigDecimal("-3"), "X") // L
        assertThat(h.winsSince("s", 0L)).isEqualTo(2)
        assertThat(h.lossesSince("s", 0L)).isEqualTo(2)
        assertThat(h.winsSince("s", 250L)).isEqualTo(1)
        assertThat(h.lossesSince("s", 250L)).isEqualTo(1)
    }

    @Test
    fun `tradesSinceFor filters by symbol`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "GOLD")
        h.recordTrade("s", 200L, BigDecimal("-5"), "SILVER")
        h.recordTrade("s", 300L, BigDecimal("20"), "GOLD")
        assertThat(h.tradesSinceFor("s", "GOLD", 0L)).isEqualTo(2)
        assertThat(h.tradesSinceFor("s", "SILVER", 0L)).isEqualTo(1)
        assertThat(h.tradesSinceFor("s", "PLATINUM", 0L)).isZero
        assertThat(h.tradesSinceFor("s", "GOLD", 150L)).isEqualTo(1)
    }

    @Test
    fun `lastTradeAtFor returns most recent fill for that symbol`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "GOLD")
        h.recordTrade("s", 200L, BigDecimal("-5"), "SILVER")
        h.recordTrade("s", 300L, BigDecimal("20"), "GOLD")
        h.recordTrade("s", 400L, BigDecimal("-3"), "SILVER")
        assertThat(h.lastTradeAtFor("s", "GOLD")).isEqualTo(300L)
        assertThat(h.lastTradeAtFor("s", "SILVER")).isEqualTo(400L)
        assertThat(h.lastTradeAtFor("s", "PLATINUM")).isNull()
    }
}
