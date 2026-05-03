package com.qkt.common

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MoneyTest {
    @Test
    fun `of(String) produces value with scale 8`() {
        val v = Money.of("2400.5")
        assertThat(v.scale()).isEqualTo(Money.SCALE)
        assertThat(v).isEqualByComparingTo(BigDecimal("2400.5"))
    }

    @Test
    fun `of(Long) and of(Int) produce values with scale 8`() {
        val long = Money.of(100L)
        val int = Money.of(42)
        assertThat(long.scale()).isEqualTo(Money.SCALE)
        assertThat(int.scale()).isEqualTo(Money.SCALE)
        assertThat(long).isEqualByComparingTo(BigDecimal("100"))
        assertThat(int).isEqualByComparingTo(BigDecimal("42"))
    }

    @Test
    fun `ZERO has scale 8 and value zero`() {
        assertThat(Money.ZERO.scale()).isEqualTo(Money.SCALE)
        assertThat(Money.ZERO).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
