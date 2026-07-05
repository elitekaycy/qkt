package com.qkt.indicators.catalog

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ReopenGapTest {
    private val minMs = 60_000L
    private val hourMs = 3_600_000L

    private fun candle(
        startTime: Long,
        open: String,
        close: String,
    ) = Candle(
        symbol = "X",
        open = BigDecimal(open),
        high = BigDecimal(close).max(BigDecimal(open)),
        low = BigDecimal(close).min(BigDecimal(open)),
        close = BigDecimal(close),
        volume = BigDecimal.ZERO,
        startTime = startTime,
        endTime = startTime + minMs,
    )

    @Test
    fun `null until the first session-boundary gap`() {
        val g = ReopenGap(minGapHours = 12)
        g.update(candle(0, "100", "100"))
        g.update(candle(1 * minMs, "100", "101")) // tiny gap, not a reopen
        assertThat(g.isReady).isFalse()
        assertThat(g.size()).isNull()
        assertThat(g.origin()).isNull()
        assertThat(g.fillFraction()).isNull()
    }

    @Test
    fun `latches gap size and origin at the reopen and tracks fill fraction`() {
        val g = ReopenGap(minGapHours = 12)
        g.update(candle(0, "100", "100"))
        g.update(candle(1 * minMs, "100", "101")) // prior-close before the break = 101
        // 13h later -> a reopen. origin = 101, reopen open = 105, gap = +4.
        val reopen = 2 * minMs + 13 * hourMs
        g.update(candle(reopen, "105", "105"))
        assertThat(g.isReady).isTrue()
        assertThat(g.size()).isEqualByComparingTo("4")
        assertThat(g.origin()).isEqualByComparingTo("101")
        assertThat(g.fillFraction()!!.toDouble()).isCloseTo(0.0, within(1e-9))
        // Retrace halfway back toward origin: close 103 -> (105-103)/4 = 0.5.
        g.update(candle(reopen + minMs, "105", "103"))
        assertThat(g.fillFraction()!!.toDouble()).isCloseTo(0.5, within(1e-9))
        // Full fill: close back at origin 101 -> 1.0.
        g.update(candle(reopen + 2 * minMs, "103", "101"))
        assertThat(g.fillFraction()!!.toDouble()).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `a down gap keeps fill fraction positive as price retraces up`() {
        val g = ReopenGap(minGapHours = 12)
        g.update(candle(0, "100", "100")) // origin = 100
        val reopen = 13 * hourMs
        g.update(candle(reopen, "96", "96")) // gap = -4
        assertThat(g.size()).isEqualByComparingTo("-4")
        g.update(candle(reopen + minMs, "96", "98")) // (96-98)/(96-100) = 0.5
        assertThat(g.fillFraction()!!.toDouble()).isCloseTo(0.5, within(1e-9))
    }

    @Test
    fun `rejects non-positive gap threshold`() {
        assertThatThrownBy { ReopenGap(minGapHours = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
