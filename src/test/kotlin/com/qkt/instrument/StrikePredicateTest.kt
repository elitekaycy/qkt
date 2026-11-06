package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A strike carries exactly the bounds its type reads, as the venue gateway's checks require. */
class StrikePredicateTest {
    private val low = BigDecimal("7400")
    private val high = BigDecimal("7425")

    @Test
    fun `each strike type takes the bounds it reads`() {
        assertThat(StrikePredicate(StrikeType.BETWEEN, low, high).cap).isEqualTo(high)
        assertThat(StrikePredicate(StrikeType.GREATER, low, null).floor).isEqualTo(low)
        assertThat(StrikePredicate(StrikeType.GREATER_OR_EQUAL, low, null).floor).isEqualTo(low)
        assertThat(StrikePredicate(StrikeType.LESS, null, high).cap).isEqualTo(high)
        assertThat(StrikePredicate(StrikeType.LESS_OR_EQUAL, null, high).cap).isEqualTo(high)
        assertThat(StrikePredicate(StrikeType.CUSTOM, null, null).type).isEqualTo(StrikeType.CUSTOM)
        assertThat(StrikePredicate(StrikeType.NONE, null, null).type).isEqualTo(StrikeType.NONE)
    }

    @Test
    fun `a range needs both bounds with the floor below the cap`() {
        assertThatThrownBy { StrikePredicate(StrikeType.BETWEEN, low, null) }.hasMessageContaining("a floor and a cap")
        assertThatThrownBy { StrikePredicate(StrikeType.BETWEEN, high, low) }.hasMessageContaining("below cap")
        assertThatThrownBy { StrikePredicate(StrikeType.BETWEEN, low, low) }.hasMessageContaining("below cap")
    }

    @Test
    fun `an above question reads a floor only and a below question a cap only`() {
        assertThatThrownBy { StrikePredicate(StrikeType.GREATER, null, null) }.hasMessageContaining("a floor only")
        assertThatThrownBy { StrikePredicate(StrikeType.GREATER, low, high) }.hasMessageContaining("a floor only")
        assertThatThrownBy { StrikePredicate(StrikeType.LESS_OR_EQUAL, low, null) }.hasMessageContaining("a cap only")
    }

    @Test
    fun `a question about a team or with no strike takes no bounds`() {
        assertThatThrownBy { StrikePredicate(StrikeType.CUSTOM, low, null) }.hasMessageContaining("no bounds")
        assertThatThrownBy { StrikePredicate(StrikeType.NONE, null, high) }.hasMessageContaining("no bounds")
    }
}
