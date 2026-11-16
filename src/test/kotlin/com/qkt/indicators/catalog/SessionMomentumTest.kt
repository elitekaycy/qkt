package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SessionMomentumTest {
    private val day0 = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L

    private fun candle(
        startTime: Long,
        open: String,
        close: String,
    ): Candle {
        val o = BigDecimal(open)
        val c = BigDecimal(close)
        return Candle(
            symbol = "X",
            open = o,
            high = o.max(c),
            low = o.min(c),
            close = c,
            volume = BigDecimal.ZERO,
            startTime = startTime,
            endTime = startTime + hourMs,
        )
    }

    @Test
    fun `sums in-window daily returns over nDays and excludes the forming day`() {
        val sm = SessionMomentum(startHour = 12, endHour = 14, nDays = 2)
        // Day 0 in-window (12:00, 13:00): open 100 -> close 102 → +0.02.
        sm.update(candle(day0 + 12 * hourMs, "100", "101"))
        sm.update(candle(day0 + 13 * hourMs, "101", "102"))
        // Out-of-window bar must be ignored.
        sm.update(candle(day0 + 3 * hourMs, "999", "999"))
        // Day 1 in-window: open 200 -> close 210 → +0.05. Its first bar finalizes Day 0.
        sm.update(candle(day0 + dayMs + 12 * hourMs, "200", "205"))
        sm.update(candle(day0 + dayMs + 13 * hourMs, "205", "210"))
        // Only one completed session so far — not ready.
        assertThat(sm.isReady).isFalse()
        assertThat(sm.value()).isNull()
        // Day 2's first in-window bar finalizes Day 1; now two completed sessions.
        sm.update(candle(day0 + 2 * dayMs + 12 * hourMs, "300", "303"))
        assertThat(sm.isReady).isTrue()
        // 0.02 + 0.05 = 0.07.
        assertThat(sm.value()).isEqualByComparingTo("0.07")
    }

    @Test
    fun `a session opening at a zero price is undefined until it leaves the window`() {
        val sm = SessionMomentum(startHour = 12, endHour = 14, nDays = 2)
        // Day 0 opens at 0: its return has no base, so it is undefined rather than an exception.
        sm.update(candle(day0 + 12 * hourMs, "0", "101"))
        // Day 1: +0.05. Day 2's first bar completes the two-day window.
        sm.update(candle(day0 + dayMs + 12 * hourMs, "200", "210"))
        sm.update(candle(day0 + 2 * dayMs + 12 * hourMs, "300", "303"))
        assertThat(sm.isReady).isTrue()
        assertThat(sm.value()).isNull()
        // Day 3's first bar completes Day 2 (+0.01) and rolls Day 0 out: 0.05 + 0.01.
        sm.update(candle(day0 + 3 * dayMs + 12 * hourMs, "400", "404"))
        assertThat(sm.value()).isEqualByComparingTo("0.06")
    }

    @Test
    fun `a session opening at a negative price is undefined`() {
        val sm = SessionMomentum(startHour = 12, endHour = 14, nDays = 1)
        sm.update(candle(day0 + 12 * hourMs, "-2", "101"))
        sm.update(candle(day0 + dayMs + 12 * hourMs, "200", "210"))
        assertThat(sm.isReady).isTrue()
        assertThat(sm.value()).isNull()
    }

    @Test
    fun `rejects a window that wraps midnight`() {
        assertThatThrownBy { SessionMomentum(startHour = 22, endHour = 2, nDays = 3) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects non-positive nDays`() {
        assertThatThrownBy { SessionMomentum(startHour = 12, endHour = 14, nDays = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
