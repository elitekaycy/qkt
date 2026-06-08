package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class RegressionSlopeTest {
    @Test
    fun `not ready before warmup`() {
        val r = RegressionSlope(3)
        r.update(Money.of("10"))
        r.update(Money.of("20"))
        assertThat(r.isReady).isFalse()
        assertThat(r.value()).isNull()
        assertThat(r.warmupBars).isEqualTo(3)
    }

    @Test
    fun `zero slope on a flat series`() {
        val r = RegressionSlope(5)
        repeat(5) { r.update(Money.of("7")) }
        assertThat(r.isReady).isTrue()
        assertThat(r.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `slope of a perfect ramp equals the step`() {
        // y = 0,1,2,3,4 against x = 0..4 → slope 1.0.
        val r = RegressionSlope(5)
        listOf("0", "1", "2", "3", "4").forEach { r.update(Money.of(it)) }
        assertThat(r.value()!!.toDouble()).isCloseTo(1.0, within(1e-7))
    }

    @Test
    fun `negative slope on a falling series`() {
        // y = 4,3,2,1,0 → slope -1.0.
        val r = RegressionSlope(5)
        listOf("4", "3", "2", "1", "0").forEach { r.update(Money.of(it)) }
        assertThat(r.value()!!.toDouble()).isCloseTo(-1.0, within(1e-7))
    }

    @Test
    fun `slope scales with the step size`() {
        // y = 0,2,4,6,8 (step 2) → slope 2.0.
        val r = RegressionSlope(5)
        listOf("0", "2", "4", "6", "8").forEach { r.update(Money.of(it)) }
        assertThat(r.value()!!.toDouble()).isCloseTo(2.0, within(1e-7))
    }

    @Test
    fun `rejects period below 2`() {
        assertThatThrownBy { RegressionSlope(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RegressionSlope(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
