package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An event contract's terms: a derivative that pays 1 per contract, with no margin and no futures-style fee. */
class EventTermsTest {
    @Test
    fun `an event contract pays 1 by default and carries no margin or per-contract fee`() {
        val terms: DerivativeTerms = EventContracts.terms()

        assertThat(terms).isInstanceOf(EventTerms::class.java)
        assertThat((terms as EventTerms).payout).isEqualByComparingTo("1")
        assertThat(terms.margin).isNull()
        assertThat(terms.exchangeFeePerContract).isEqualByComparingTo("0")
        assertThat(terms.takerFeeRate).isEqualByComparingTo("0")
        assertThat(terms.fee).isNull()
        assertThat(terms.canCloseEarly).isFalse()
        assertThat(terms.exclusive).isFalse()
    }

    @Test
    fun `a settled outcome may be anywhere from 0 to the payout`() {
        assertThat(EventContracts.terms(outcome = BigDecimal.ZERO).outcome).isEqualByComparingTo("0")
        assertThat(EventContracts.terms(outcome = BigDecimal.ONE).outcome).isEqualByComparingTo("1")
        assertThat(EventContracts.terms(BigDecimal.TEN, BigDecimal("10")).outcome).isEqualByComparingTo("10")
    }

    @Test
    fun `an outcome outside 0 to the payout is refused`() {
        assertThatThrownBy { EventContracts.terms(outcome = BigDecimal("1.01")) }.hasMessageContaining("outcome")
        assertThatThrownBy { EventContracts.terms(outcome = BigDecimal("-0.01")) }.hasMessageContaining("outcome")
    }

    @Test
    fun `a payout must be positive`() {
        assertThatThrownBy { EventContracts.terms(payout = BigDecimal.ZERO) }.hasMessageContaining("payout")
    }

    @Test
    fun `trading cannot stop before it opens, though it may open and stop at once`() {
        val terms = EventContracts.terms()

        assertThat(terms.copy(closeAtMs = terms.openAtMs).closeAtMs).isEqualTo(terms.openAtMs)
        assertThatThrownBy { terms.copy(closeAtMs = terms.openAtMs - 1) }.hasMessageContaining("closeAtMs")
    }

    @Test
    fun `the family root is VENUE colon SERIES and the event code is not blank`() {
        val terms = EventContracts.terms()

        assertThat(terms.copy(root = "POLY:will_it_rain").root).isEqualTo("POLY:will_it_rain")
        assertThatThrownBy { terms.copy(root = "KXINXU") }.hasMessageContaining("VENUE:SERIES")
        assertThatThrownBy { terms.copy(event = " ") }.hasMessageContaining("event")
    }
}
