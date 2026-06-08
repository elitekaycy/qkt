package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WilliamsRTest {
    private fun candle(
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(c), Money.of(h), Money.of(l), Money.of(c), Money.of("1"), 0L, 1L)

    @Test
    fun `not ready before warmup`() {
        val w = WilliamsR(3)
        w.update(candle("10", "8", "9"))
        w.update(candle("12", "9", "11"))
        assertThat(w.isReady).isFalse()
        assertThat(w.value()).isNull()
        assertThat(w.warmupBars).isEqualTo(3)
    }

    @Test
    fun `computes percent-R against the window high-low range`() {
        // HH=12, LL=8, last close=10.5 → -100*(12-10.5)/(12-8) = -37.5.
        val w = WilliamsR(3)
        w.update(candle("10", "8", "9"))
        w.update(candle("12", "9", "11"))
        w.update(candle("11", "9.5", "10.5"))
        assertThat(w.value()).isEqualByComparingTo(Money.of("-37.5"))
    }

    @Test
    fun `flat range yields minus 100`() {
        val w = WilliamsR(3)
        repeat(3) { w.update(candle("10", "10", "10")) }
        assertThat(w.value()).isEqualByComparingTo(Money.of("-100"))
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { WilliamsR(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
