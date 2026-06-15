package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class BasketCompositorTest {
    private val hour = 3_600_000L

    private fun bar(
        close: String,
        i: Int,
    ): Candle {
        val c = BigDecimal(close)
        return Candle("X", c, c, c, c, BigDecimal.ONE, i * hour, (i + 1) * hour)
    }

    private fun window(
        i: Int,
        a: String,
        b: String,
    ): Map<String, Candle> = mapOf("a" to bar(a, i), "b" to bar(b, i))

    @Test
    fun `first aligned window returns null - only the baseline is set`() {
        val c = BasketCompositor("BASKET:AB", listOf("a", "b"))
        assertThat(c.onAligned(window(0, "1.00", "1.00"))).isNull()
    }

    @Test
    fun `second window with plus one percent on both moves the index to about 101`() {
        val c = BasketCompositor("BASKET:AB", listOf("a", "b"))
        c.onAligned(window(0, "1.00", "1.00"))
        val candle = c.onAligned(window(1, "1.01", "1.01"))!!
        assertThat(candle.open).isEqualByComparingTo(BigDecimal("100"))
        assertThat(candle.close).isCloseTo(BigDecimal("101"), within(BigDecimal("0.0001")))
    }

    @Test
    fun `plus one percent then minus one percent returns the index to about 100`() {
        val c = BasketCompositor("BASKET:AB", listOf("a", "b"))
        c.onAligned(window(0, "1.00", "1.00"))
        c.onAligned(window(1, "1.01", "1.01"))
        val candle = c.onAligned(window(2, "1.00", "1.00"))!!
        assertThat(candle.close).isCloseTo(BigDecimal("100"), within(BigDecimal("0.0001")))
    }

    @Test
    fun `a flat window keeps the index at the base`() {
        val c = BasketCompositor("BASKET:AB", listOf("a", "b"))
        c.onAligned(window(0, "1.00", "1.00"))
        val candle = c.onAligned(window(1, "1.00", "1.00"))!!
        assertThat(candle.open).isEqualByComparingTo(BigDecimal("100"))
        assertThat(candle.close).isCloseTo(BigDecimal("100"), within(BigDecimal("0.0001")))
    }

    @Test
    fun `the synthesized candle carries index OHLC, zero volume, and the window`() {
        val c = BasketCompositor("BASKET:AB", listOf("a", "b"))
        c.onAligned(window(0, "1.00", "1.00"))
        // A rising window: open is the prior index (100), close is higher, so high=close, low=open.
        val candle = c.onAligned(window(1, "1.02", "1.02"))!!
        assertThat(candle.symbol).isEqualTo("BASKET:AB")
        assertThat(candle.high).isEqualByComparingTo(candle.close)
        assertThat(candle.low).isEqualByComparingTo(candle.open)
        assertThat(candle.volume).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(candle.startTime).isEqualTo(hour)
        assertThat(candle.endTime).isEqualTo(2 * hour)
    }

    @Test
    fun `a falling window puts low at close and high at open`() {
        val c = BasketCompositor("BASKET:AB", listOf("a", "b"))
        c.onAligned(window(0, "1.00", "1.00"))
        val candle = c.onAligned(window(1, "0.98", "0.98"))!!
        assertThat(candle.high).isEqualByComparingTo(candle.open)
        assertThat(candle.low).isEqualByComparingTo(candle.close)
        assertThat(candle.close).isLessThan(BigDecimal("100"))
    }
}
