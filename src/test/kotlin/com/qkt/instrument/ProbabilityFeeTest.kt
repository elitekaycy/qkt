package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An event contract's fee shape: rates are fractions in [0, 1) and the rounding unit is positive. */
class ProbabilityFeeTest {
    private fun fee(
        taker: String = "0.07",
        maker: String? = "0.0175",
        rounding: String = "0.01",
    ) = ProbabilityFee(BigDecimal(taker), maker?.let(::BigDecimal), BigDecimal(rounding))

    @Test
    fun `a taker rate, an optional maker rate and a rounding unit`() {
        assertThat(fee().makerRate).isEqualByComparingTo("0.0175")
        assertThat(fee(maker = null).makerRate).isNull()
        assertThat(fee(taker = "0", maker = "0").takerRate).isEqualByComparingTo("0")
    }

    @Test
    fun `a rate of 1 or more, or below 0, is refused`() {
        assertThatThrownBy { fee(taker = "1") }.hasMessageContaining("takerRate")
        assertThatThrownBy { fee(taker = "-0.01") }.hasMessageContaining("takerRate")
        assertThatThrownBy { fee(maker = "1.5") }.hasMessageContaining("makerRate")
        assertThatThrownBy { fee(maker = "-0.01") }.hasMessageContaining("makerRate")
    }

    @Test
    fun `the rounding unit must be positive`() {
        assertThatThrownBy { fee(rounding = "0") }.hasMessageContaining("rounding")
    }
}
