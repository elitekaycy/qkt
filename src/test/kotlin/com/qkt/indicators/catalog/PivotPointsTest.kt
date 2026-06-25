package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PivotPointsTest {
    private val day0 = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L

    private fun candle(
        startTime: Long,
        open: String,
        high: String,
        low: String,
        close: String,
    ) = Candle(
        symbol = "X",
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal.ZERO,
        startTime = startTime,
        endTime = startTime + hourMs,
    )

    @Test
    fun `null until the first day completes`() {
        val pp = PivotPoints()
        pp.update(candle(day0, "100", "110", "95", "105"))
        pp.update(candle(day0 + 12 * hourMs, "105", "108", "90", "102"))
        assertThat(pp.isReady).isFalse()
        assertThat(pp.value()).isNull()
    }

    @Test
    fun `latches prior day OHLC into P, R1 and S1`() {
        val pp = PivotPoints()
        // Day 0 cumulative: high 110, low 90, close 102 (last bar's close).
        pp.update(candle(day0, "100", "110", "95", "105"))
        pp.update(candle(day0 + 12 * hourMs, "105", "108", "90", "102"))
        // Any Day 1 bar latches Day 0's pivots.
        pp.update(candle(day0 + dayMs, "102", "103", "101", "102"))
        assertThat(pp.isReady).isTrue()
        // P = (110+90+102)/3 = 100.66666667; R1 = 2P - 90 = 111.33333333; S1 = 2P - 110 = 91.33333333.
        val lv = pp.levels()!!
        assertThat(lv.p).isEqualByComparingTo("100.66666667")
        assertThat(lv.r1).isEqualByComparingTo("111.33333333")
        assertThat(lv.s1).isEqualByComparingTo("91.33333333")
        assertThat(pp.value()).isEqualByComparingTo("100.66666667")
    }
}
