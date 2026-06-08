package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class CorrelationTest {
    @Test
    fun `not ready before warmup`() {
        val c = Correlation(3)
        c.update(Money.of("1"), Money.of("1"))
        c.update(Money.of("2"), Money.of("2"))
        assertThat(c.isReady).isFalse()
        assertThat(c.value()).isNull()
        assertThat(c.warmupBars).isEqualTo(3)
    }

    @Test
    fun `correlation of two identical series is one`() {
        val c = Correlation(5)
        listOf(1, 2, 3, 4, 5).forEach { c.update(Money.of("$it"), Money.of("$it")) }
        assertThat(c.value()!!.toDouble()).isCloseTo(1.0, within(1e-7))
    }

    @Test
    fun `correlation of perfectly anti-correlated series is minus one`() {
        val c = Correlation(5)
        listOf(1, 2, 3, 4, 5).zip(listOf(5, 4, 3, 2, 1)).forEach { (a, b) ->
            c.update(Money.of("$a"), Money.of("$b"))
        }
        assertThat(c.value()!!.toDouble()).isCloseTo(-1.0, within(1e-7))
    }

    @Test
    fun `matches the textbook pearson r on a known pair`() {
        // x = 1,2,3,4,5 ; y = 1,3,2,5,4 → sxy 8, sxx 10, syy 10 → r = 0.8.
        val c = Correlation(5)
        listOf(1, 2, 3, 4, 5).zip(listOf(1, 3, 2, 5, 4)).forEach { (a, b) ->
            c.update(Money.of("$a"), Money.of("$b"))
        }
        assertThat(c.value()!!.toDouble()).isCloseTo(0.8, within(1e-7))
    }

    @Test
    fun `null when one series is flat`() {
        val c = Correlation(5)
        listOf(1, 2, 3, 4, 5).forEach { c.update(Money.of("$it"), Money.of("7")) }
        assertThat(c.isReady).isTrue()
        assertThat(c.value()).isNull()
    }

    @Test
    fun `rolls the window after N pairs`() {
        val c = Correlation(3)
        // identical → 1.0
        listOf(1, 2, 3).forEach { c.update(Money.of("$it"), Money.of("$it")) }
        assertThat(c.value()!!.toDouble()).isCloseTo(1.0, within(1e-7))
        // push an anti-correlated pair set through the window
        c.update(Money.of("4"), Money.of("0"))
        c.update(Money.of("5"), Money.of("-1"))
        c.update(Money.of("6"), Money.of("-2"))
        // window now 4,5,6 vs 0,-1,-2 → still monotonic opposite → -1.0
        assertThat(c.value()!!.toDouble()).isCloseTo(-1.0, within(1e-7))
    }

    @Test
    fun `rejects period below 2`() {
        assertThatThrownBy { Correlation(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Correlation(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
