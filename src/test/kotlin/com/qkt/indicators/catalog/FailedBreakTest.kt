package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FailedBreakTest {
    private val minMs = 60_000L

    private fun candle(
        i: Int,
        high: String,
        low: String,
        close: String,
    ) = Candle(
        symbol = "X",
        open = BigDecimal(close),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal.ZERO,
        startTime = i * minMs,
        endTime = i * minMs + minMs,
    )

    @Test
    fun `null until the range window fills`() {
        val fb = FailedBreak(rangeLen = 3, reclaimBars = 3, armBars = 2, high = true)
        fb.update(candle(0, "10", "9", "10"))
        assertThat(fb.isReady).isFalse()
        assertThat(fb.value()).isNull()
        fb.update(candle(1, "11", "10", "11"))
        fb.update(candle(2, "12", "11", "12"))
        // Boundary (max of the 3 prior highs) is only ready on the next bar.
        assertThat(fb.warmupBars).isEqualTo(4)
    }

    @Test
    fun `arms after a high pierce is reclaimed inside, for armBars bars`() {
        val fb = FailedBreak(rangeLen = 3, reclaimBars = 3, armBars = 2, high = true)
        fb.update(candle(0, "10", "9", "10"))
        fb.update(candle(1, "11", "10", "11"))
        fb.update(candle(2, "12", "11", "12")) // prior-3 highs now 10,11,12 -> boundary 12
        fb.update(candle(3, "15", "12", "14")) // pierce: high 15 > 12, close 14 still outside
        assertThat(fb.isReady).isTrue()
        assertThat(fb.value()).isEqualByComparingTo("0")
        fb.update(candle(4, "14", "10", "11")) // close 11 back inside the boundary 12 -> failed break
        assertThat(fb.value()).isEqualByComparingTo("1")
        fb.update(candle(5, "12", "10", "11")) // still armed (armBars = 2)
        assertThat(fb.value()).isEqualByComparingTo("1")
        fb.update(candle(6, "12", "10", "11")) // arm expired
        assertThat(fb.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `a pierce that never reclaims within the window does not arm`() {
        val fb = FailedBreak(rangeLen = 3, reclaimBars = 2, armBars = 2, high = true)
        fb.update(candle(0, "10", "9", "10"))
        fb.update(candle(1, "11", "10", "11"))
        fb.update(candle(2, "12", "11", "12"))
        fb.update(candle(3, "15", "12", "14")) // pierce, close stays outside
        fb.update(candle(4, "16", "13", "15")) // still outside
        fb.update(candle(5, "17", "14", "16")) // window elapsed, still outside -> continuation
        assertThat(fb.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `low variant arms after a low pierce is reclaimed`() {
        val fb = FailedBreak(rangeLen = 3, reclaimBars = 3, armBars = 1, high = false)
        fb.update(candle(0, "11", "10", "10"))
        fb.update(candle(1, "10", "9", "9"))
        fb.update(candle(2, "9", "8", "8")) // prior-3 lows 10,9,8 -> boundary 8
        fb.update(candle(3, "8", "5", "6")) // pierce down: low 5 < 8, close 6 still below
        assertThat(fb.value()).isEqualByComparingTo("0")
        fb.update(candle(4, "10", "6", "9")) // close 9 back above 8 -> failed break
        assertThat(fb.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `rejects non-positive parameters`() {
        assertThatThrownBy { FailedBreak(rangeLen = 0, reclaimBars = 3, armBars = 2, high = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FailedBreak(rangeLen = 3, reclaimBars = 0, armBars = 2, high = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FailedBreak(rangeLen = 3, reclaimBars = 3, armBars = 0, high = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
