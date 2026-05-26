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
        assertThat(view.tradesToday(1_000_000_000_000L)).isZero
        assertThat(view.winsToday(1_000_000_000_000L)).isZero
        assertThat(view.lossesToday(1_000_000_000_000L)).isZero
    }

    @Test
    fun `tradesSince counts only entries at or after the cutoff`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"))
        h.recordTrade("s", 200L, BigDecimal("-5"))
        h.recordTrade("s", 300L, BigDecimal("20"))
        assertThat(h.tradesSince("s", 0L)).isEqualTo(3)
        assertThat(h.tradesSince("s", 200L)).isEqualTo(2)
        assertThat(h.tradesSince("s", 250L)).isEqualTo(1)
        assertThat(h.tradesSince("s", 400L)).isZero
    }

    @Test
    fun `winsSince and lossesSince filter by outcome`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10")) // W
        h.recordTrade("s", 200L, BigDecimal("-5")) // L
        h.recordTrade("s", 300L, BigDecimal("20")) // W
        h.recordTrade("s", 400L, BigDecimal("-3")) // L
        assertThat(h.winsSince("s", 0L)).isEqualTo(2)
        assertThat(h.lossesSince("s", 0L)).isEqualTo(2)
        assertThat(h.winsSince("s", 250L)).isEqualTo(1)
        assertThat(h.lossesSince("s", 250L)).isEqualTo(1)
    }

    @Test
    fun `view tradesToday computes UTC-midnight cutoff from now`() {
        val h = TradeHistory()
        // 2024-01-15 00:00:00 UTC = 1705276800000 ms
        val midnight = 1_705_276_800_000L
        h.recordTrade("s", midnight - 1, BigDecimal("10")) // yesterday — not counted
        h.recordTrade("s", midnight + 1_000, BigDecimal("10")) // today
        h.recordTrade("s", midnight + 7_200_000, BigDecimal("-5")) // today
        val view: TradeHistoryView = TradeHistoryViewImpl(h, "s")

        // "now" anywhere in 2024-01-15 → cutoff is 00:00:00 UTC that day
        val now = midnight + 12 * 3600 * 1000 // noon UTC
        assertThat(view.tradesToday(now)).isEqualTo(2)
        assertThat(view.winsToday(now)).isEqualTo(1)
        assertThat(view.lossesToday(now)).isEqualTo(1)
    }

    @Test
    fun `view tradesToday is empty when nothing was recorded today`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10")) // epoch — way before any "today"
        val view: TradeHistoryView = TradeHistoryViewImpl(h, "s")
        val now2024 = 1_705_276_800_000L // 2024-01-15 UTC
        assertThat(view.tradesToday(now2024)).isZero
        assertThat(view.winsToday(now2024)).isZero
        assertThat(view.lossesToday(now2024)).isZero
    }
}
