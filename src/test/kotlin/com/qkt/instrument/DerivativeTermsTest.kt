package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DerivativeTermsTest {
    @Test
    fun `maintenance margin above initial is refused`() {
        assertThatThrownBy { MarginTerms(BigDecimal("100"), BigDecimal("150"), MarginBasis.PER_CONTRACT) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maintenance")
    }

    @Test
    fun `a notional margin rate above one is refused`() {
        assertThatThrownBy { MarginTerms(BigDecimal("1.5"), BigDecimal("0.5"), MarginBasis.NOTIONAL) }
            .hasMessageContaining("NOTIONAL")
    }

    @Test
    fun `negative fees are refused`() {
        assertThatThrownBy { FutureTerms("CME:ES", 1L, exchangeFeePerContract = BigDecimal("-1")) }
            .hasMessageContaining("exchangeFeePerContract")
    }

    @Test
    fun `a root must be venue-qualified`() {
        assertThatThrownBy { FutureTerms("ES", 1L) }.hasMessageContaining("VENUE:ROOT")
    }

    @Test
    fun `a continuous view has no expiry`() {
        assertThat(FutureTerms("CME:ES", expiryMs = null).expiryMs).isNull()
    }

    @Test
    fun `a perpetual with an expiry is refused`() {
        assertThatThrownBy { FutureTerms("DERIBIT:BTC_USDC", 1L, perpetual = true) }.hasMessageContaining("perpetual")
    }
}
