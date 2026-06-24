package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class SkewTest {
    private fun feed(
        ind: Skew,
        vararg prices: String,
    ) = prices.forEach { ind.update(BigDecimal(it)) }

    @Test
    fun `null before warmup`() {
        val s = Skew(period = 4)
        // period counts returns, so 4 returns need 5 prices; 4 prices is one short.
        feed(s, "100", "101", "102", "103")
        assertThat(s.isReady).isFalse()
        assertThat(s.value()).isNull()
        assertThat(s.warmupBars).isEqualTo(5)
    }

    @Test
    fun `symmetric returns have zero skew`() {
        val s = Skew(period = 4)
        // returns -0.01, +0.01, -0.01, +0.01 — mean 0, cubes cancel → skew 0.
        feed(s, "100", "99", "99.99", "98.9901", "99.980001")
        assertThat(s.isReady).isTrue()
        assertThat(s.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `negative skew when one sharp drop dominates`() {
        val s = Skew(period = 4)
        // returns +0.01, +0.01, +0.01, -0.03 — mean 0; m3 = -6e-6, sigma^3 = 5.19615e-6.
        feed(s, "100", "101", "102.01", "103.0301", "99.939197")
        assertThat(s.value()!!.toDouble()).isCloseTo(-1.154700538, within(1e-7))
    }

    @Test
    fun `positive skew when one sharp jump dominates`() {
        val s = Skew(period = 4)
        // returns -0.01, -0.01, -0.01, +0.03 — mirror of the negative case → +1.1547.
        feed(s, "100", "99", "98.01", "97.0299", "99.940797")
        assertThat(s.value()!!.toDouble()).isCloseTo(1.154700538, within(1e-7))
    }

    @Test
    fun `window rolls forward dropping the oldest return`() {
        val s = Skew(period = 4)
        // First price 1000 only seeds prevPrice; the +0.01/+0.01/+0.01/-0.03 window
        // is the same as the negative-skew case, so the stale leading bar must not shift it.
        feed(s, "1000", "100", "101", "102.01", "103.0301", "99.939197")
        assertThat(s.value()!!.toDouble()).isCloseTo(-1.154700538, within(1e-7))
    }

    @Test
    fun `rejects period below three`() {
        assertThatThrownBy { Skew(period = 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
