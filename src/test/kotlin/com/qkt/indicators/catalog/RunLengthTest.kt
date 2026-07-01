package com.qkt.indicators.catalog

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunLengthTest {
    private fun feed(
        ind: RunLength,
        vararg values: String,
    ) = values.forEach { ind.update(BigDecimal(it)) }

    @Test
    fun `null before the first change is seen`() {
        val rl = RunLength()
        assertThat(rl.isReady).isFalse()
        assertThat(rl.value()).isNull()
        assertThat(rl.warmupBars).isEqualTo(1)
        rl.update(BigDecimal("100"))
        // One value: no close-to-close change yet.
        assertThat(rl.isReady).isFalse()
        assertThat(rl.value()).isNull()
    }

    @Test
    fun `counts consecutive up-closes as a positive streak`() {
        val rl = RunLength()
        feed(rl, "100", "101", "102", "103")
        assertThat(rl.isReady).isTrue()
        assertThat(rl.value()).isEqualByComparingTo("3")
    }

    @Test
    fun `counts consecutive down-closes as a negative streak`() {
        val rl = RunLength()
        feed(rl, "100", "99", "98")
        assertThat(rl.value()).isEqualByComparingTo("-2")
    }

    @Test
    fun `a direction change restarts the streak at plus or minus one`() {
        val rl = RunLength()
        feed(rl, "100", "101", "102") // +2
        assertThat(rl.value()).isEqualByComparingTo("2")
        rl.update(BigDecimal("101")) // down-close flips the streak
        assertThat(rl.value()).isEqualByComparingTo("-1")
    }

    @Test
    fun `an unchanged close breaks the streak to zero`() {
        val rl = RunLength()
        feed(rl, "100", "101", "102") // +2
        rl.update(BigDecimal("102")) // flat
        assertThat(rl.isReady).isTrue()
        assertThat(rl.value()).isEqualByComparingTo("0")
    }
}
