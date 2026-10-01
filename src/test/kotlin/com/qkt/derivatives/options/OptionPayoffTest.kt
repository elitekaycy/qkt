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

    private fun leg(
        right: OptionRight,
        strike: String,
        quantity: String,
        size: String = "1",
    ) = OptionLeg(right, BigDecimal(strike), BigDecimal(quantity), BigDecimal(size))

    private val c = OptionRight.CALL
    private val p = OptionRight.PUT

    private fun min(vararg legs: OptionLeg) = OptionPayoff.minimum(legs.toList())

    @Test
    fun `as single options, longs never lose at expiry, a short put loses its strike, a short call is unbounded`() {
        assertThat(min(leg(c, "100", "1"))).isEqualByComparingTo("0")
        assertThat(min(leg(p, "100", "1"))).isEqualByComparingTo("0")
        assertThat(min(leg(p, "100", "-1"))).isEqualByComparingTo("-100")
        assertThat(min(leg(c, "100", "-1"))).isNull()
        assertThat(min()).isEqualByComparingTo("0")
    }

    @Test
    fun `spreads and condors lose at most their width`() {
        assertThat(min(leg(c, "100", "1"), leg(c, "110", "-1"))).isEqualByComparingTo("0")
        assertThat(min(leg(c, "100", "-1"), leg(c, "110", "1"))).isEqualByComparingTo("-10")
        assertThat(min(leg(p, "110", "-1"), leg(p, "100", "1"))).isEqualByComparingTo("-10")
        assertThat(
            min(leg(p, "95", "-1"), leg(p, "90", "1"), leg(c, "105", "-1"), leg(c, "110", "1")),
        ).isEqualByComparingTo("-5")
        assertThat(min(leg(c, "90", "1"), leg(c, "100", "-2"), leg(c, "110", "1"))).isEqualByComparingTo("0")
    }

    @Test
    fun `a short call covered by fewer long calls is still unbounded, and sizes scale the loss`() {
        assertThat(min(leg(c, "100", "-1"), leg(c, "110", "0.5"))).isNull()
        assertThat(min(leg(p, "100", "-0.5", size = "10"))).isEqualByComparingTo("-500")
    }
}
