package com.qkt.pnl

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradeHistoryStreakTest {
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
        h.recordTrade("s", 100L, BigDecimal.ZERO, "X")
        assertThat(h.lastTradeAt("s")).isNull()
        assertThat(h.winStreak("s")).isZero
    }

    @Test
    fun `lastTradeAt returns most recent timestamp`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X")
        h.recordTrade("s", 200L, BigDecimal("-5"), "X")
        h.recordTrade("s", 300L, BigDecimal("20"), "X")
        assertThat(h.lastTradeAt("s")).isEqualTo(300L)
    }

    @Test
    fun `lastTradePnl returns most recent pnl`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X")
        h.recordTrade("s", 200L, BigDecimal("-7"), "X")
        assertThat(h.lastTradePnl("s")).isEqualByComparingTo("-7")
    }

    @Test
    fun `winStreak counts consecutive wins from newest`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("-5"), "X")
        h.recordTrade("s", 200L, BigDecimal("10"), "X")
        h.recordTrade("s", 300L, BigDecimal("15"), "X")
        h.recordTrade("s", 400L, BigDecimal("20"), "X")
        assertThat(h.winStreak("s")).isEqualTo(3)
        assertThat(h.lossStreak("s")).isZero
    }

    @Test
    fun `lossStreak counts consecutive losses from newest`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X")
        h.recordTrade("s", 200L, BigDecimal("-5"), "X")
        h.recordTrade("s", 300L, BigDecimal("-7"), "X")
        assertThat(h.lossStreak("s")).isEqualTo(2)
        assertThat(h.winStreak("s")).isZero
    }

    @Test
    fun `banked sums current win streak pnl and resets after a loss`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("-5"), "X")
        h.recordTrade("s", 200L, BigDecimal("10.50"), "X")
        h.recordTrade("s", 300L, BigDecimal("15.25"), "X")
        assertThat(h.banked("s")).isEqualByComparingTo("25.75")

        h.recordTrade("s", 400L, BigDecimal("-1"), "X")
        assertThat(h.banked("s")).isEqualByComparingTo("0")
    }

    @Test
    fun `restore reloads persisted streak state`() {
        val persistor = com.qkt.persistence.NoopStatePersistor()
        val first = TradeHistory(persistor = persistor)
        first.recordTrade("s", 100L, BigDecimal("-5"), "X")
        first.recordTrade("s", 200L, BigDecimal("10"), "X")
        first.recordTrade("s", 300L, BigDecimal("15"), "X")

        val restarted = TradeHistory(persistor = persistor)
        restarted.restore("s")

        assertThat(restarted.winStreak("s")).isEqualTo(2)
        assertThat(restarted.lossStreak("s")).isZero
        assertThat(restarted.banked("s")).isEqualByComparingTo("25")
        assertThat(restarted.lastTradeAt("s")).isEqualTo(300L)
    }

    @Test
    fun `streak breaks at the first non-matching outcome`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X") // W
        h.recordTrade("s", 200L, BigDecimal("10"), "X") // W
        h.recordTrade("s", 300L, BigDecimal("-5"), "X") // L
        h.recordTrade("s", 400L, BigDecimal("10"), "X") // W → streak = 1
        assertThat(h.winStreak("s")).isEqualTo(1)
    }

    @Test
    fun `streaks are per-strategy`() {
        val h = TradeHistory()
        h.recordTrade("a", 100L, BigDecimal("10"), "X")
        h.recordTrade("b", 100L, BigDecimal("-5"), "X")
        assertThat(h.winStreak("a")).isEqualTo(1)
        assertThat(h.lossStreak("a")).isZero
        assertThat(h.winStreak("b")).isZero
        assertThat(h.lossStreak("b")).isEqualTo(1)
    }

    @Test
    fun `buffer caps at maxHistory and evicts oldest`() {
        val h = TradeHistory(maxHistory = 3)
        h.recordTrade("s", 100L, BigDecimal("-1"), "X")
        h.recordTrade("s", 200L, BigDecimal("-1"), "X")
        h.recordTrade("s", 300L, BigDecimal("-1"), "X")
        h.recordTrade("s", 400L, BigDecimal("-1"), "X") // evicts the 100L entry
        assertThat(h.lossStreak("s")).isEqualTo(3) // buffer holds 3, all losses
        assertThat(h.lastTradeAt("s")).isEqualTo(400L)
    }
}
