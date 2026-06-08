package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class BetaTest {
    @Test
    fun `not ready before warmup`() {
        val b = Beta(3)
        b.update(Money.of("1"), Money.of("1"))
        assertThat(b.isReady).isFalse()
        assertThat(b.value()).isNull()
        assertThat(b.warmupBars).isEqualTo(3)
    }

    @Test
    fun `beta of a series against itself is one`() {
        val b = Beta(5)
        listOf(1, 2, 3, 4, 5).forEach { b.update(Money.of("$it"), Money.of("$it")) }
        assertThat(b.value()!!.toDouble()).isCloseTo(1.0, within(1e-7))
    }

    @Test
    fun `beta is the slope when the dependent scales the independent`() {
        // a = 2 * b → beta = Cov(a,b)/Var(b) = 2.
        val b = Beta(5)
        listOf(1, 2, 3, 4, 5).forEach { b.update(Money.of("${2 * it}"), Money.of("$it")) }
        assertThat(b.value()!!.toDouble()).isCloseTo(2.0, within(1e-7))
    }

    @Test
    fun `negative beta when the series move opposite`() {
        // a = -b → beta = -1.
        val b = Beta(5)
        listOf(1, 2, 3, 4, 5).forEach { b.update(Money.of("${-it}"), Money.of("$it")) }
        assertThat(b.value()!!.toDouble()).isCloseTo(-1.0, within(1e-7))
    }

    @Test
    fun `null when the independent series is flat`() {
        val b = Beta(5)
        listOf(1, 2, 3, 4, 5).forEach { b.update(Money.of("$it"), Money.of("7")) }
        assertThat(b.isReady).isTrue()
        assertThat(b.value()).isNull()
    }

    @Test
    fun `rejects period below 2`() {
        assertThatThrownBy { Beta(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Beta(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
