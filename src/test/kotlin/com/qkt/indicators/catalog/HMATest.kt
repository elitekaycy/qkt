package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HMATest {
    @Test
    fun `not ready before warmup`() {
        val h = HMA(16)
        repeat(4) { h.update(Money.of("10")) }
        assertThat(h.isReady).isFalse()
        assertThat(h.value()).isNull()
        // period 16, sqrt(16)=4 → warmup 16 + 4 - 1 = 19.
        assertThat(h.warmupBars).isEqualTo(19)
    }

    @Test
    fun `settles to the constant on a flat series`() {
        // WMA of a constant is the constant → raw = 2c - c = c → hull WMA = c.
        val h = HMA(16)
        repeat(40) { h.update(Money.of("100")) }
        assertThat(h.value()).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `rejects period below 2`() {
        assertThatThrownBy { HMA(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
