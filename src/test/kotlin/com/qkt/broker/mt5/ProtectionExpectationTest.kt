package com.qkt.broker.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProtectionExpectationTest {
    @Test
    fun `full-precision engine request matches the venue-quantized report`() {
        // bot2 2026-08-24 08:00: engine pushed a trailing stop at full Money precision;
        // the venue reported it back at gold's 3 digits and the poller alarmed (#1063).
        assertThat(
            ProtectionExpectation.matchesVenue(BigDecimal("4457.74345678"), BigDecimal("4457.743")),
        ).isTrue()
        assertThat(
            ProtectionExpectation.matchesVenue(BigDecimal("4457.74250000"), BigDecimal("4457.743")),
        ).isTrue()
    }

    @Test
    fun `identical values match regardless of trailing zeros`() {
        assertThat(
            ProtectionExpectation.matchesVenue(BigDecimal("4457.743000"), BigDecimal("4457.743")),
        ).isTrue()
    }

    @Test
    fun `a genuinely different level at the venue's own scale still fails`() {
        assertThat(
            ProtectionExpectation.matchesVenue(BigDecimal("4457.743"), BigDecimal("4457.744")),
        ).isFalse()
        assertThat(
            ProtectionExpectation.matchesVenue(BigDecimal("4456.137"), BigDecimal("4457.743")),
        ).isFalse()
    }

    @Test
    fun `no expectation never matches`() {
        assertThat(ProtectionExpectation.matchesVenue(null, BigDecimal("1.10000"))).isFalse()
    }
}
