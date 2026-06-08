package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class VarianceTest {
    @Test
    fun `not ready before warmup`() {
        val v = Variance(3)
        v.update(Money.of("10"))
        assertThat(v.isReady).isFalse()
        assertThat(v.value()).isNull()
        assertThat(v.warmupBars).isEqualTo(3)
    }

    @Test
    fun `zero on a constant series`() {
        val v = Variance(5)
        repeat(5) { v.update(Money.of("7")) }
        assertThat(v.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `equals the sample stddev squared on a known series`() {
        // 2,4,4,4,5,5,7,9 → sample variance 32/7 ≈ 4.5714286 (Stddev² from StddevTest).
        val v = Variance(8)
        listOf("2", "4", "4", "4", "5", "5", "7", "9").forEach { v.update(Money.of(it)) }
        assertThat(v.value()!!.toDouble()).isCloseTo(32.0 / 7.0, within(1e-6))
    }

    @Test
    fun `rejects period below 2`() {
        assertThatThrownBy { Variance(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
