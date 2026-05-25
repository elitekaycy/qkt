package com.qkt.pnl

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradeHistoryTest {
    @Test
    fun `empty history returns null and zero streaks`() {
        val h = TradeHistory()
        assertThat(h.lastTradeAt("s")).isNull()
        assertThat(h.lastTradePnl("s")).isNull()
        assertThat(h.winStreak("s")).isZero
        assertThat(h.lossStreak("s")).isZero
    }

    @Test
    fun `zero-pnl fills are skipped (they're position-opens, not closes)`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal.ZERO)
        assertThat(h.lastTradeAt("s")).isNull()
        assertThat(h.winStreak("s")).isZero
    }

    @Test
    fun `lastTradeAt returns most recent timestamp`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"))
        h.recordTrade("s", 200L, BigDecimal("-5"))
        h.recordTrade("s", 300L, BigDecimal("20"))
        assertThat(h.lastTradeAt("s")).isEqualTo(300L)
    }

    @Test
    fun `lastTradePnl returns most recent pnl`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"))
        h.recordTrade("s", 200L, BigDecimal("-7"))
        assertThat(h.lastTradePnl("s")).isEqualByComparingTo("-7")
    }

    @Test
    fun `winStreak counts consecutive wins from newest`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("-5"))
        h.recordTrade("s", 200L, BigDecimal("10"))
        h.recordTrade("s", 300L, BigDecimal("15"))
        h.recordTrade("s", 400L, BigDecimal("20"))
        assertThat(h.winStreak("s")).isEqualTo(3)
        assertThat(h.lossStreak("s")).isZero
    }

    @Test
    fun `lossStreak counts consecutive losses from newest`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"))
        h.recordTrade("s", 200L, BigDecimal("-5"))
        h.recordTrade("s", 300L, BigDecimal("-7"))
        assertThat(h.lossStreak("s")).isEqualTo(2)
        assertThat(h.winStreak("s")).isZero
    }

    @Test
    fun `streak breaks at the first non-matching outcome`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10")) // W
        h.recordTrade("s", 200L, BigDecimal("10")) // W
        h.recordTrade("s", 300L, BigDecimal("-5")) // L
        h.recordTrade("s", 400L, BigDecimal("10")) // W → streak = 1
        assertThat(h.winStreak("s")).isEqualTo(1)
    }

    @Test
    fun `streaks are per-strategy`() {
        val h = TradeHistory()
        h.recordTrade("a", 100L, BigDecimal("10"))
        h.recordTrade("b", 100L, BigDecimal("-5"))
        assertThat(h.winStreak("a")).isEqualTo(1)
        assertThat(h.lossStreak("a")).isZero
        assertThat(h.winStreak("b")).isZero
        assertThat(h.lossStreak("b")).isEqualTo(1)
    }

    @Test
    fun `buffer caps at maxHistory and evicts oldest`() {
        val h = TradeHistory(maxHistory = 3)
        h.recordTrade("s", 100L, BigDecimal("-1"))
        h.recordTrade("s", 200L, BigDecimal("-1"))
        h.recordTrade("s", 300L, BigDecimal("-1"))
        h.recordTrade("s", 400L, BigDecimal("-1")) // evicts the 100L entry
        assertThat(h.lossStreak("s")).isEqualTo(3) // buffer holds 3, all losses
        assertThat(h.lastTradeAt("s")).isEqualTo(400L)
    }

    @Test
    fun `view delegates per-strategy`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"))
        val view: TradeHistoryView = TradeHistoryViewImpl(h, "s")
        assertThat(view.lastTradeAt()).isEqualTo(100L)
        assertThat(view.lastTradePnl()).isEqualByComparingTo("10")
        assertThat(view.winStreak()).isEqualTo(1)
    }

    @Test
    fun `noop view returns nulls and zero streaks`() {
        val view: TradeHistoryView = NoOpTradeHistoryView()
        assertThat(view.lastTradeAt()).isNull()
        assertThat(view.lastTradePnl()).isNull()
        assertThat(view.winStreak()).isZero
        assertThat(view.lossStreak()).isZero
    }
}
