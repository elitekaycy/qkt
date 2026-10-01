package com.qkt.derivatives.options

import com.qkt.instrument.OptionRight
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptionPayoffTest {
    @Test
    fun `intrinsic value is what exercise pays per unit and never negative`() {
        val k = BigDecimal("84000")
        assertThat(OptionPayoff.intrinsic(OptionRight.CALL, k, BigDecimal("84042.83"))).isEqualByComparingTo("42.83")
        assertThat(OptionPayoff.intrinsic(OptionRight.CALL, k, BigDecimal("83000"))).isEqualByComparingTo("0")
        assertThat(OptionPayoff.intrinsic(OptionRight.PUT, k, BigDecimal("83000"))).isEqualByComparingTo("1000")
        assertThat(OptionPayoff.intrinsic(OptionRight.PUT, k, BigDecimal("84000"))).isEqualByComparingTo("0")
    }
}
