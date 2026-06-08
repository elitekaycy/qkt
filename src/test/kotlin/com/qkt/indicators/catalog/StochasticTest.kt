package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StochasticTest {
    private fun candle(
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(c), Money.of(h), Money.of(l), Money.of(c), Money.of("1"), 0L, 1L)

    @Test
    fun `not ready before warmup`() {
        val s = Stochastic(kPeriod = 3, dPeriod = 1)
        s.update(candle("10", "8", "9"))
        s.update(candle("12", "9", "11"))
        assertThat(s.isReady).isFalse()
        assertThat(s.lines()).isNull()
        assertThat(s.warmupBars).isEqualTo(3)
    }

    @Test
    fun `percent-K is the close position within the range`() {
        // HH=12, LL=8, close=10.5 → %K = 100*(10.5-8)/(12-8) = 62.5; %D = SMA(%K,1) = 62.5.
        val s = Stochastic(kPeriod = 3, dPeriod = 1)
        s.update(candle("10", "8", "9"))
        s.update(candle("12", "9", "11"))
        s.update(candle("11", "9.5", "10.5"))
        val lines = s.lines()!!
        assertThat(lines.k).isEqualByComparingTo(Money.of("62.5"))
        assertThat(lines.d).isEqualByComparingTo(Money.of("62.5"))
    }

    @Test
    fun `no range yields the midpoint`() {
        val s = Stochastic(kPeriod = 3, dPeriod = 1)
        repeat(3) { s.update(candle("10", "10", "10")) }
        assertThat(s.lines()!!.k).isEqualByComparingTo(Money.of("50"))
    }

    @Test
    fun `rejects non-positive periods`() {
        assertThatThrownBy { Stochastic(0, 3) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Stochastic(14, 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
