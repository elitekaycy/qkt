package com.qkt.indicators.catalog

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LagTest {
    private fun feed(
        ind: Lag,
        vararg xs: String,
    ) = xs.forEach { ind.update(BigDecimal(it)) }

    @Test
    fun `null before n plus one bars`() {
        val l = Lag(n = 2)
        feed(l, "100", "101")
        assertThat(l.isReady).isFalse()
        assertThat(l.value()).isNull()
        assertThat(l.warmupBars).isEqualTo(3)
    }

    @Test
    fun `reports the value n bars ago`() {
        val l = Lag(n = 2)
        feed(l, "100", "101", "102")
        assertThat(l.isReady).isTrue()
        assertThat(l.value()).isEqualByComparingTo("100")
    }

    @Test
    fun `window rolls forward dropping the oldest bar`() {
        val l = Lag(n = 2)
        feed(l, "100", "101", "102", "103", "104")
        // current bar is 104; two bars ago is 102.
        assertThat(l.value()).isEqualByComparingTo("102")
    }

    @Test
    fun `lag of one is the previous bar`() {
        val l = Lag(n = 1)
        feed(l, "10", "20", "30")
        assertThat(l.value()).isEqualByComparingTo("20")
    }

    @Test
    fun `returns the input verbatim without rounding`() {
        val l = Lag(n = 1)
        feed(l, "1.23456789", "2.0")
        assertThat(l.value()).isEqualByComparingTo("1.23456789")
    }

    @Test
    fun `rejects non-positive n`() {
        assertThatThrownBy { Lag(n = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
