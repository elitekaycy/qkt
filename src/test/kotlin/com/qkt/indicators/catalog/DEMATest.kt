package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DEMATest {
    @Test
    fun `not ready before warmup`() {
        val d = DEMA(5)
        repeat(4) { d.update(Money.of("10")) }
        assertThat(d.isReady).isFalse()
        assertThat(d.value()).isNull()
        assertThat(d.warmupBars).isEqualTo(9)
    }

    @Test
    fun `settles to the constant on a flat series`() {
        // EMA of a constant is that constant, so DEMA = 2c - c = c exactly.
        val d = DEMA(5)
        repeat(20) { d.update(Money.of("10")) }
        assertThat(d.value()).isEqualByComparingTo(Money.of("10"))
    }

    @Test
    fun `tracks a rising series above its EMA-equivalent lag`() {
        val d = DEMA(3)
        var x = 1
        repeat(30) { d.update(Money.of("${x++}")) }
        // On a steady ramp DEMA closely tracks the latest value (low lag); just assert it's risen.
        assertThat(d.value()!!.toDouble()).isGreaterThan(20.0)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { DEMA(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
