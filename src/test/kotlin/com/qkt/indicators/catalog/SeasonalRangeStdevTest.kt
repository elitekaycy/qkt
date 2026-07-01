package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class SeasonalRangeStdevTest {
    private val day0 = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L

    // Range is high - low; low fixed at 100 so the range equals high - 100.
    private fun candle(
        startTime: Long,
        high: String,
    ) = Candle(
        symbol = "X",
        open = BigDecimal("100"),
        high = BigDecimal(high),
        low = BigDecimal("100"),
        close = BigDecimal("100"),
        volume = BigDecimal.ZERO,
        startTime = startTime,
        endTime = startTime + hourMs,
    )

    @Test
    fun `sample stdev of the hour after window prior occurrences`() {
        val sr = SeasonalRangeStdev(window = 3)
        // Same UTC hour on consecutive days, ranges 2, 4, 6, 8.
        sr.update(candle(day0, "102"))
        assertThat(sr.isReady).isFalse()
        assertThat(sr.value()).isNull()
        sr.update(candle(day0 + dayMs, "104"))
        assertThat(sr.value()).isNull()
        sr.update(candle(day0 + 2 * dayMs, "106"))
        assertThat(sr.value()).isNull()
        sr.update(candle(day0 + 3 * dayMs, "108"))
        // bucket held [2, 4, 6] -> mean 4, sample var (8/(3-1)) = 4, stdev = 2.
        assertThat(sr.isReady).isTrue()
        assertThat(sr.value()!!.toDouble()).isCloseTo(2.0, within(1e-6))
    }

    @Test
    fun `each UTC hour keeps an independent baseline`() {
        val sr = SeasonalRangeStdev(window = 2)
        sr.update(candle(day0, "102")) // hour 0, range 2
        sr.update(candle(day0 + 13 * hourMs, "110")) // hour 13, range 10 — different bucket
        sr.update(candle(day0 + dayMs, "104")) // hour 0, bucket [2] only -> null
        assertThat(sr.value()).isNull()
        sr.update(candle(day0 + 2 * dayMs, "108")) // hour 0, bucket [2,4] -> stdev = sqrt(2)
        assertThat(sr.value()!!.toDouble()).isCloseTo(Math.sqrt(2.0), within(1e-6))
    }

    @Test
    fun `rejects window below two`() {
        assertThatThrownBy { SeasonalRangeStdev(window = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
