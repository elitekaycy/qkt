package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BookLimitsUnitTest {
    @Test
    fun `a cap written as money is refused, saying what the number means`() {
        assertThatThrownBy { BookLimits(maxGrossExposure = BigDecimal("300000")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("max_gross_exposure is a multiple of book capital")
            .hasMessageContaining("300000x capital")
    }

    @Test
    fun `multiples of capital are accepted`() {
        val limits = BookLimits(BigDecimal("3.0"), BigDecimal("1.5"), BigDecimal("0.35"))

        assertThat(limits.maxGrossExposure).isEqualByComparingTo("3.0")
    }

    @Test
    fun `zero and negative caps are refused`() {
        assertThatThrownBy {
            BookLimits(
                maxNetExposure = BigDecimal.ZERO,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
