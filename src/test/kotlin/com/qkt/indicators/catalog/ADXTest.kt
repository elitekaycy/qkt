package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ADXTest {
    private fun candle(
        h: Int,
        l: Int,
        c: Int,
    ) = Candle("X", Money.of("$c"), Money.of("$h"), Money.of("$l"), Money.of("$c"), Money.of("1"), 0L, 1L)

    @Test
    fun `not ready before warmup`() {
        val adx = ADX(14)
        repeat(14) { adx.update(candle(100 + it, 99 + it, 100 + it)) }
        assertThat(adx.isReady).isFalse()
        assertThat(adx.lines()).isNull()
        assertThat(adx.warmupBars).isEqualTo(28)
    }

    @Test
    fun `clean uptrend drives plus-DI above minus-DI with a strong ADX`() {
        val adx = ADX(14)
        repeat(40) { adx.update(candle(100 + it, 99 + it, 100 + it)) }
        val lines = adx.lines()!!
        assertThat(lines.plusDi).isGreaterThan(lines.minusDi)
        assertThat(lines.adx.toDouble()).isGreaterThan(0.0)
        assertThat(lines.adx.toDouble()).isLessThanOrEqualTo(100.0)
    }

    @Test
    fun `clean downtrend drives minus-DI above plus-DI`() {
        val adx = ADX(14)
        repeat(40) { adx.update(candle(100 - it, 99 - it, 100 - it)) }
        val lines = adx.lines()!!
        assertThat(lines.minusDi).isGreaterThan(lines.plusDi)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { ADX(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
