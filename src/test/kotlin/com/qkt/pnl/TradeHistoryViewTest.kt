package com.qkt.pnl

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradeHistoryViewTest {
    @Test
    fun `view delegates per-strategy`() {
        val h = TradeHistory()
        h.recordTrade("s", 100L, BigDecimal("10"), "X")
        val view: TradeHistoryView = TradeHistoryViewImpl(h, "s")
        assertThat(view.lastTradeAt()).isEqualTo(100L)
        assertThat(view.lastTradePnl()).isEqualByComparingTo("10")
        assertThat(view.winStreak()).isEqualTo(1)
        assertThat(view.banked()).isEqualByComparingTo("10")
    }

    @Test
    fun `noop view returns nulls and zero streaks`() {
        val view: TradeHistoryView = NoOpTradeHistoryView()
        assertThat(view.lastTradeAt()).isNull()
        assertThat(view.lastTradePnl()).isNull()
        assertThat(view.winStreak()).isZero
        assertThat(view.lossStreak()).isZero
        assertThat(view.banked()).isEqualByComparingTo("0")
        assertThat(view.tradesToday(1_000_000_000_000L)).isZero
        assertThat(view.winsToday(1_000_000_000_000L)).isZero
        assertThat(view.lossesToday(1_000_000_000_000L)).isZero
        assertThat(view.tradesTodayFor("X", 1_000_000_000_000L)).isZero
        assertThat(view.lastTradeAtFor("X")).isNull()
    }

    @Test
    fun `view tradesToday computes UTC-midnight cutoff from now`() {
        val h = TradeHistory()
        // 2024-01-15 00:00:00 UTC = 1705276800000 ms
        val midnight = 1_705_276_800_000L
        h.recordTrade("s", midnight - 1, BigDecimal("10"), "X") // yesterday — not counted
        h.recordTrade("s", midnight + 1_000, BigDecimal("10"), "X") // today
        h.recordTrade("s", midnight + 7_200_000, BigDecimal("-5"), "X") // today
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
        h.recordTrade("s", 100L, BigDecimal("10"), "X") // epoch — way before any "today"
        val view: TradeHistoryView = TradeHistoryViewImpl(h, "s")
        val now2024 = 1_705_276_800_000L // 2024-01-15 UTC
        assertThat(view.tradesToday(now2024)).isZero
        assertThat(view.winsToday(now2024)).isZero
        assertThat(view.lossesToday(now2024)).isZero
    }

    @Test
    fun `view tradesTodayFor and lastTradeAtFor delegate per symbol`() {
        val h = TradeHistory()
        val midnight = 1_705_276_800_000L
        h.recordTrade("s", midnight - 1, BigDecimal("10"), "GOLD") // yesterday, excluded
        h.recordTrade("s", midnight + 1_000, BigDecimal("10"), "GOLD") // today
        h.recordTrade("s", midnight + 7_200_000, BigDecimal("-5"), "SILVER") // today, other symbol
        val view: TradeHistoryView = TradeHistoryViewImpl(h, "s")
        val now = midnight + 12 * 3600 * 1000
        assertThat(view.tradesTodayFor("GOLD", now)).isEqualTo(1)
        assertThat(view.tradesTodayFor("SILVER", now)).isEqualTo(1)
        assertThat(view.lastTradeAtFor("GOLD")).isEqualTo(midnight + 1_000)
        assertThat(view.lastTradeAtFor("SILVER")).isEqualTo(midnight + 7_200_000)
        assertThat(view.lastTradeAtFor("PLATINUM")).isNull()
    }
}
